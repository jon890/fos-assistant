package com.bifos.assistant.usage.domain;

import java.time.Instant;

/**
 * 한 구간의 실행을 provider 와 모델로 묶은 합계다.
 *
 * <p>어느 모델을 더 싼 것으로 바꿀 수 있는지 답하려고 만든다. 칸의 뜻은 {@link CostByAgent} 와 같다.
 *
 * @param provider 모델을 제공한 곳. 끝나지 않은 실행이 아니어도 비어 있을 수 있다
 * @param model 모델 이름
 */
public record CostByModel(
        String provider,
        String model,
        Long executions,
        Long estimatedMicros,
        Long actualMicros,
        Long inputTokens,
        Long outputTokens,
        Double avgContextChars,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
