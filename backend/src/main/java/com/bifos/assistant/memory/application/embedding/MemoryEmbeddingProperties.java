package com.bifos.assistant.memory.application.embedding;

import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 기억 임베딩 서비스와 채우는 작업의 설정이다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>{@code baseUrl} 과 {@code model} 이 모두 있어야 켜진다. 둘 중 하나라도 비면 임베딩을 부르지 않고 키워드 점수만 쓴다. {@code application.yml} 은 모델에
 * 운영 기본 이름을 두므로 주소가 켜고 끄는 값이다. 임베딩 서비스는 자체 서버의
 * 로컬 모델이라 API key 를 두지 않는다. 운영 값은 이 저장소에 적지 않는다.
 *
 * @param baseUrl OpenAI 호환 {@code POST /v1/embeddings} 를 받는 주소. 끝의 {@code /v1/embeddings} 는 붙이지 않는다
 * @param model 요청의 {@code model} 값. 저장한 벡터가 어느 모델의 것인지도 이 값으로 가른다
 * @param queryTimeout 질문 하나를 임베딩할 때 기다리는 상한. 넘으면 키워드 점수만 쓴다
 * @param batchTimeout 채우는 작업이 묶음 하나를 임베딩할 때 기다리는 상한
 * @param batchSize 채우는 작업이 한 번에 보내는 항목 수
 * @param maxBatchesPerRun 채우는 작업 한 번이 보내는 묶음 수의 상한
 * @param maxTextChars 임베딩할 글(제목과 본문)의 최대 글자 수. 넘는 뒤쪽은 잘라 보낸다
 * @param refreshDelay 채우는 작업이 끝나고 다음 작업까지 쉬는 시간
 */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.embedding")
public record MemoryEmbeddingProperties(
    String baseUrl,
    String model,
    Duration queryTimeout,
    Duration batchTimeout,
    @Min(1) Integer batchSize,
    @Min(1) Integer maxBatchesPerRun,
    @Min(1) Integer maxTextChars,
    Duration refreshDelay) {

    public MemoryEmbeddingProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        model = model == null ? "" : model.strip();
        queryTimeout = positiveOr(queryTimeout, Duration.ofMillis(400));
        batchTimeout = positiveOr(batchTimeout, Duration.ofSeconds(30));
        batchSize = batchSize == null ? 16 : batchSize;
        maxBatchesPerRun = maxBatchesPerRun == null ? 16 : maxBatchesPerRun;
        maxTextChars = maxTextChars == null ? 4_000 : maxTextChars;
        refreshDelay = positiveOr(refreshDelay, Duration.ofSeconds(60));
        if (!baseUrl.isEmpty() && !httpUrl(baseUrl)) {
            // 주소가 틀리면 모든 질문의 임베딩이 실패한다. 값은 운영 설정이라 메시지에 넣지 않는다
            throw new IllegalArgumentException("memory embedding: base-url 은 http 나 https 주소여야 한다");
        }
    }


    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }


    /** 꺼진 설정이다. 테스트와 측정이 쓴다. */
    public static MemoryEmbeddingProperties disabled() {
        return new MemoryEmbeddingProperties(null, null, null, null, null, null, null, null);
    }


    public boolean enabled() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }


    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
