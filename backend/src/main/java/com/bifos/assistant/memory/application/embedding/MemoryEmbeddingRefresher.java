package com.bifos.assistant.memory.application.embedding;

import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.domain.MemoryEmbeddingSource;
import com.bifos.assistant.memory.domain.MemoryEmbeddingStamp;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    private final Clock clock;


    /** 끝난 뒤 {@code refresh-delay} 만큼 쉬고 다시 돈다. 꺼져 있으면 바로 끝난다. */
    @Scheduled(
        fixedDelayString = "${assistant.memory.embedding.refresh-delay}",
        initialDelayString = "${assistant.memory.embedding.refresh-delay}")
    public void refresh() {
        if (properties.enabled()) {
            refreshOnce();
        }
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
            List<MemoryEmbeddingSource> batch = pending.subList(start, Math.min(start + properties.batchSize(), pending.size()));
            List<float[]> vectors;
            try {
                vectors = client.embed(batch.stream().map(this::textOf).toList(), properties.batchTimeout());
            } catch (EmbeddingUnavailableException ex) {
                log.warn("memory embedding refresh stopped exceptionClass={} written={} pending={}",
                    ex.getClass().getSimpleName(), written, pending.size() - written);
                break;
            }
            transaction.executeWithoutResult(status -> write(batch, vectors));
            batch.forEach(source -> vectorCache.evict(source.memoryId()));
            written += batch.size();
            batches++;
        }
        if (removed > 0 || written > 0) {
            log.info("memory embedding refreshed removed={} written={} pending={}", removed, written, pending.size() - written);
        }
        return written;
    }


    /** 줄이 없거나 모델이나 지문이 다른 대상이다. 번호 순이다. */
    private List<MemoryEmbeddingSource> pending() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> {
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
