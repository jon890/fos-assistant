package com.bifos.assistant.usage.domain;

import java.time.Instant;

/**
 * 한 구간의 실행을 실행 당시의 문맥 지문으로 묶은 합계다.
 *
 * <p>무엇이 달라져서 비용이 움직였는지 답하려고 만든다. {@code firstSeenAt} 과 {@code lastSeenAt} 이
 * 그 지문이 쓰인 구간을 말한다.
 *
 * <p>지문이 비어 있는 실행은 이 묶음에 들어오지 않는다. 지문을 모르는 것끼리 묶어도 견줄 것이 없기
 * 때문이다.
 *
 * @param fingerprint 실행 당시의 문맥 지문
 */
public record CostByFingerprint(
        String fingerprint,
        Long executions,
        Long estimatedMicros,
        Long actualMicros,
        Long inputTokens,
        Long outputTokens,
        Double avgContextChars,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
