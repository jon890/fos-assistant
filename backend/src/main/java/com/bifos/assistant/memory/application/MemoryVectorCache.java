package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.RelevanceCandidate;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.domain.MemoryEmbeddingVector;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 저장한 벡터를 프로세스 안에 둔다. 매 질문마다 모든 벡터를 데이터베이스에서 읽지 않으려는 것이다.
 *
 * <p>후보의 지금 글로 채우는 작업과 같은 지문을 계산해, 모델과 지문이 같은 줄만 쓴다. 본문을 고친 항목의 낡은 벡터는 쓰지 않는다. 캐시에 없는 번호만 한 번에
 * 기억 항목 줄의 {@code embedding_*} 칸에서 읽는다. 서버 인스턴스가 하나라 여러 프로세스가 맞추는 장치를 두지 않는다. 채우는 작업이 벡터를 바꾸거나 비우면 그 번호를 뺀다.
 */
@Component
@RequiredArgsConstructor
public class MemoryVectorCache {

    private final MemoryEmbeddingRepository embeddings;
    private final MemoryEmbeddingProperties properties;
    private final MemoryTexts texts;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();


    /** 본문이 있는 후보 가운데 지금 글과 모델에 맞는 벡터가 있는 것만 낸다. 주인 key 로 지문을 만들지 못한 후보는 뺀다. */
    public Map<Long, float[]> vectorsFor(List<RelevanceCandidate> candidates) {
        Map<Long, byte[]> fingerprints = new HashMap<>();
        for (RelevanceCandidate candidate : candidates) {
            if (candidate.plainBody() != null) {
                String text = EmbeddingText.of(candidate.title(), candidate.plainBody(), properties.maxTextChars());
                texts.embeddingFingerprint(candidate.scope(), candidate.ownerId(), properties.model(), text)
                    .ifPresent(fingerprint -> fingerprints.put(candidate.memoryId(), fingerprint));
            }
        }
        // 캐시의 지문이 지금 글과 다르면 없는 것으로 보고 다시 읽는다. 채우는 작업이 줄을 바꾼 뒤에 옛 줄을 캐시에 넣은 경우도 여기서 바로잡힌다
        List<Long> missing = new ArrayList<>();
        fingerprints.forEach((memoryId, fingerprint) -> {
            if (!matches(cache.get(memoryId), fingerprint)) {
                missing.add(memoryId);
            }
        });
        if (!missing.isEmpty()) {
            for (MemoryEmbeddingVector row : embeddings.findVectors(missing)) {
                cache.put(row.memoryId(), new Cached(row.model(), row.textFingerprint(), row.vector()));
            }
        }
        Map<Long, float[]> vectors = new HashMap<>();
        fingerprints.forEach((memoryId, fingerprint) -> {
            Cached cached = cache.get(memoryId);
            if (matches(cached, fingerprint)) {
                vectors.put(memoryId, cached.vector());
            }
        });
        return vectors;
    }


    private boolean matches(Cached cached, byte[] fingerprint) {
        return cached != null && cached.model().equals(properties.model()) && Arrays.equals(cached.fingerprint(), fingerprint);
    }


    /** 채우는 작업이 벡터를 넣거나 바꾸거나 비운 뒤, 항목을 지운 뒤 부른다. */
    public void evict(Long memoryId) {
        cache.remove(memoryId);
    }


    /** 모두 비운다. 저장한 벡터를 한꺼번에 비운 테스트가 쓴다. */
    public void clear() {
        cache.clear();
    }


    private record Cached(String model, byte[] fingerprint, float[] vector) {
    }
}
