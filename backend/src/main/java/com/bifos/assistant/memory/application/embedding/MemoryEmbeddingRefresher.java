package com.bifos.assistant.memory.application.embedding;

import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.domain.MemoryEmbeddingSource;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 임베딩 대상 항목의 벡터를 채우고 낡은 줄을 지운다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>한 번에 대상이 아닌 줄과 항목이 없어진 줄을 지우고, 줄이 없거나 지문이 다른 대상을 번호 순으로 {@code batchSize} 씩
 * {@code maxBatchesPerRun} 묶음까지 임베딩한다. 묶음 하나가 실패하면 그 주기를 끝내고 다음 주기에 다시 한다. 임베딩 호출 동안 트랜잭션을 열어 두지
 * 않는다. 로그에는 건수와 예외 클래스 이름만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryEmbeddingRefresher {

    private final MemoryEmbeddingProperties properties;
    private final EmbeddingClient client;
    private final MemoryEmbeddingRepository embeddings;
    private final PlatformTransactionManager transactionManager;
    private final MemoryVectorCache vectorCache;
    private final BackgroundTasks backgroundTasks;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();


    /**
     * {@code refresh-delay} 마다 한 주기를 가상 스레드에서 띄운다. 꺼져 있거나 앞 주기가 아직 돌면 아무것도 하지 않는다.
     *
     * <p>임베딩 호출은 묶음마다 수십 초까지 걸릴 수 있어, 다른 일정과 함께 쓰는 스케줄러 스레드에서 돌리지 않는다.
     */
    @Scheduled(
        fixedDelayString = "${assistant.memory.embedding.refresh-delay}",
        initialDelayString = "${assistant.memory.embedding.refresh-delay}")
    public void refresh() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        backgroundTasks.start("memory-embedding-refresh", () -> {
            try {
                refreshOnce();
            } catch (RuntimeException ex) {
                log.warn("memory embedding refresh failed exceptionClass={}", ex.getClass().getSimpleName());
            } finally {
                running.set(false);
            }
        });
    }


    /**
     * 한 주기를 돈다. 테스트가 직접 부른다.
     *
     * @return 넣거나 바꾼 줄 수. 꺼져 있으면 0
     */
    public int refreshOnce() {
        if (!properties.enabled()) {
            return 0;
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Integer removed = transaction.execute(status -> {
            List<Long> stale = embeddings.findStaleMemoryIds();
            embeddings.deleteAllByIdInBatch(stale);
            stale.forEach(vectorCache::evict);
            return stale.size();
        });
        List<MemoryEmbeddingSource> pending = pending();
        int written = 0;
        int batches = 0;
        for (int start = 0; start < pending.size() && batches < properties.maxBatchesPerRun(); start += properties.batchSize()) {
            List<Long> ids = pending.subList(start, Math.min(start + properties.batchSize(), pending.size())).stream()
                .map(MemoryEmbeddingSource::memoryId)
                .toList();
            // 주기 처음에 읽은 글은 몇 분 지났을 수 있다. 그 사이 민감으로 바뀐 항목의 평문을 보내지 않게 보내기 직전에 다시 읽는다
            List<MemoryEmbeddingSource> batch = readOnly().execute(status -> embeddings.findEmbeddableSourcesByIds(ids));
            batches++;
            if (batch.isEmpty()) {
                continue;
            }
            Embedded embedded = embedBatch(batch);
            if (embedded == null) {
                log.warn("memory embedding refresh stopped written={} pending={}", written, Math.max(0, pending.size() - written));
                break;
            }
            transaction.executeWithoutResult(status -> write(embedded.sources(), embedded.vectors()));
            embedded.sources().forEach(source -> vectorCache.evict(source.memoryId()));
            written += embedded.sources().size();
        }
        if (removed > 0 || written > 0) {
            log.info("memory embedding refreshed removed={} written={} pending={}", removed, written, Math.max(0, pending.size() - written));
        }
        return written;
    }


    /** 줄이 없거나 모델이나 지문이 다른 대상이다. 번호 순이다. */
    private List<MemoryEmbeddingSource> pending() {
        return readOnly().execute(status -> {
            Map<Long, MemoryEmbeddingStamp> stamps = embeddings.findStamps().stream()
                .collect(Collectors.toMap(MemoryEmbeddingStamp::memoryId, Function.identity()));
            return embeddings.findEmbeddableSources().stream()
                .filter(source -> {
                    MemoryEmbeddingStamp stamp = stamps.get(source.memoryId());
                    return stamp == null
                        || !stamp.model().equals(properties.model())
                        || !Arrays.equals(stamp.textFingerprint(), EmbeddingText.fingerprint(properties.model(), textOf(source)));
                })
                .toList();
        });
    }


    /**
     * 묶음을 한 번에 보낸다. 거절되면 한 건씩 다시 보내 거절한 항목만 건너뛴다. 거절한 글이 하나라도 있으면 묶음 전체가 거절되므로, 그 항목이 뒤 항목을
     * 막지 않게 하려는 것이다.
     *
     * @return 받아들인 항목과 그 벡터. 서비스가 거절이 아닌 이유로 실패하면 null 이고, 그 주기를 끝낸다
     */
    private Embedded embedBatch(List<MemoryEmbeddingSource> batch) {
        try {
            return new Embedded(batch, client.embed(batch.stream().map(this::textOf).toList(), properties.batchTimeout()));
        } catch (EmbeddingRejectedException ex) {
            return embedEach(batch);
        } catch (EmbeddingUnavailableException ex) {
            log.warn("memory embedding batch failed exceptionClass={}", ex.getClass().getSimpleName());
            return null;
        }
    }


    /**
     * 묶음을 한 건씩 보낸다. 거절한 항목은 번호만 남기고 건너뛴다.
     *
     * @return 받아들인 항목과 그 벡터. 서비스가 거절이 아닌 이유로 실패하면 null 이고, 그 주기를 끝낸다
     */
    private Embedded embedEach(List<MemoryEmbeddingSource> batch) {
        List<MemoryEmbeddingSource> sources = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        for (MemoryEmbeddingSource source : batch) {
            try {
                vectors.add(client.embed(List.of(textOf(source)), properties.batchTimeout()).getFirst());
                sources.add(source);
            } catch (EmbeddingRejectedException ex) {
                log.warn("memory embedding skipped a rejected item memoryId={}", source.memoryId());
            } catch (EmbeddingUnavailableException ex) {
                return null;
            }
        }
        return new Embedded(sources, vectors);
    }


    private record Embedded(List<MemoryEmbeddingSource> sources, List<float[]> vectors) {
    }


    private TransactionTemplate readOnly() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly;
    }


    private void write(List<MemoryEmbeddingSource> batch, List<float[]> vectors) {
        Map<Long, MemoryEmbedding> existing = embeddings.findByMemoryIdIn(batch.stream().map(MemoryEmbeddingSource::memoryId).toList())
            .stream()
            .collect(Collectors.toMap(MemoryEmbedding::memoryId, Function.identity()));
        for (int i = 0; i < batch.size(); i++) {
            MemoryEmbeddingSource source = batch.get(i);
            byte[] fingerprint = EmbeddingText.fingerprint(properties.model(), textOf(source));
            MemoryEmbedding row = existing.get(source.memoryId());
            if (row == null) {
                embeddings.save(MemoryEmbedding.of(source.memoryId(), properties.model(), fingerprint, vectors.get(i), clock.instant()));
            } else {
                row.replace(properties.model(), fingerprint, vectors.get(i), clock.instant());
            }
        }
    }


    private String textOf(MemoryEmbeddingSource source) {
        return EmbeddingText.of(source.title(), source.content(), properties.maxTextChars());
    }
}
