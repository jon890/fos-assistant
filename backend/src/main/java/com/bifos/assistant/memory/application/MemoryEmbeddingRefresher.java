package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.port.EmbeddingClient;
import com.bifos.assistant.memory.application.port.EmbeddingRejectedException;
import com.bifos.assistant.memory.application.port.EmbeddingUnavailableException;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.domain.MemoryEmbeddingSource;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@code maxBatchesPerRun} 묶음까지 임베딩한다. 묶음이 실패하면 한 건씩 다시 보내 실패한 항목만 건너뛰고, 그것도 모두 실패하면 그 주기를 끝내고 다음 주기에 다시 한다. 임베딩 호출 동안 트랜잭션을 열어 두지
 * 않는다. 로그에는 건수와 예외 클래스 이름만 남긴다.
 *
 * <p>주기마다 새로 쓴 항목은 {@link MemoryLinkDiscovery} 에 넘겨 대체 후보와 관련 기억을 찾는다(ADR-20261015 / memory-link).
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
    private final MemoryLinkDiscovery linkDiscovery;
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
        List<Long> writtenIds = new ArrayList<>();
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
                log.warn(
                    "memory embedding refresh stopped written={} pending={}",
                    writtenIds.size(),
                    Math.max(0, pending.size() - writtenIds.size()));
                break;
            }
            List<Long> stored = transaction.execute(status -> write(embedded.sources(), embedded.vectors()));
            embedded.sources().forEach(source -> vectorCache.evict(source.memoryId()));
            writtenIds.addAll(stored == null ? List.of() : stored);
        }
        int written = writtenIds.size();
        // 새로 쓴 벡터로 대체 후보와 관련 기억을 찾는다. 연결을 찾지 못해도 채운 벡터는 그대로 둔다
        linkDiscovery.discover(writtenIds);
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
     * 묶음을 한 번에 보낸다. 실패하면 한 건씩 다시 보내 실패한 항목만 건너뛴다. 대상은 번호 순이라, 늘 실패하는 글이나 늦는 묶음이 앞에 있으면 뒤 항목이
     * 끝내 채워지지 않기 때문이다. 건너뛴 항목은 다음 주기에 다시 본다.
     *
     * @return 받아들인 항목과 그 벡터. 한 건씩 보낸 것까지 모두 실패하면 서비스가 내려간 것으로 보고 null 을 내며, 그 주기를 끝낸다
     */
    private Embedded embedBatch(List<MemoryEmbeddingSource> batch) {
        try {
            return new Embedded(batch, client.embed(batch.stream().map(this::textOf).toList(), properties.batchTimeout()));
        } catch (EmbeddingUnavailableException ex) {
            log.warn("memory embedding batch failed, retrying one by one exceptionClass={} size={}", ex.getClass().getSimpleName(), batch.size());
            if (batch.size() == 1) {
                return ex instanceof EmbeddingRejectedException ? new Embedded(List.of(), List.of()) : null;
            }
            return embedEach(batch);
        }
    }


    /** 묶음을 한 건씩 보낸다. 실패한 항목은 번호만 남기고 건너뛴다. 모두 실패하면 null 이다. */
    private Embedded embedEach(List<MemoryEmbeddingSource> batch) {
        List<MemoryEmbeddingSource> sources = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        boolean reached = false;
        for (MemoryEmbeddingSource source : batch) {
            try {
                vectors.add(client.embed(List.of(textOf(source)), properties.batchTimeout()).getFirst());
                sources.add(source);
                reached = true;
            } catch (EmbeddingRejectedException ex) {
                reached = true;
                log.warn("memory embedding skipped a rejected item memoryId={}", source.memoryId());
            } catch (EmbeddingUnavailableException ex) {
                log.warn("memory embedding skipped a failed item memoryId={} exceptionClass={}", source.memoryId(), ex.getClass().getSimpleName());
            }
        }
        return reached ? new Embedded(sources, vectors) : null;
    }


    private record Embedded(List<MemoryEmbeddingSource> sources, List<float[]> vectors) {
    }


    private TransactionTemplate readOnly() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly;
    }


    /**
     * 임베딩한 벡터를 넣거나 바꾼다. 임베딩을 기다리는 동안 지워졌거나 대상에서 빠진 항목은 쓰지 않는다. 그 확인 뒤에 지운 항목의 줄이 남으면 다음 주기의 정리가
     * 지운다.
     *
     * @return 넣거나 바꾼 줄의 항목 번호
     */
    private List<Long> write(List<MemoryEmbeddingSource> batch, List<float[]> vectors) {
        List<Long> ids = batch.stream().map(MemoryEmbeddingSource::memoryId).toList();
        Set<Long> stillEligible = embeddings.findEmbeddableSourcesByIds(ids).stream()
            .map(MemoryEmbeddingSource::memoryId)
            .collect(Collectors.toSet());
        Map<Long, MemoryEmbedding> existing = embeddings.findByMemoryIdIn(ids)
            .stream()
            .collect(Collectors.toMap(MemoryEmbedding::memoryId, Function.identity()));
        List<Long> written = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            MemoryEmbeddingSource source = batch.get(i);
            if (!stillEligible.contains(source.memoryId())) {
                continue;
            }
            written.add(source.memoryId());
            byte[] fingerprint = EmbeddingText.fingerprint(properties.model(), textOf(source));
            MemoryEmbedding row = existing.get(source.memoryId());
            if (row == null) {
                embeddings.save(MemoryEmbedding.of(source.memoryId(), properties.model(), fingerprint, vectors.get(i)));
            } else {
                row.replace(properties.model(), fingerprint, vectors.get(i));
            }
        }
        return written;
    }


    private String textOf(MemoryEmbeddingSource source) {
        return EmbeddingText.of(source.title(), source.content(), properties.maxTextChars());
    }
}
