package com.bifos.assistant.usage.domain;

import java.time.Instant;

/**
 * 한 구간의 실행을 에이전트로 묶은 합계다.
 *
 * <p>어느 에이전트가 비용을 가장 많이 쓰는지 답하려고 만든다. 금액이 비어 있는 묶음은 0 으로 채우지
 * 않고 null 로 둔다. 0 은 공짜라는 뜻으로 읽히기 때문이다.
 *
 * @param agentId 묶은 에이전트의 번호
 * @param agentCode 에이전트 코드. 에이전트가 지워졌으면 null
 * @param agentName 에이전트 이름. 에이전트가 지워졌으면 null
 * @param executions 그 묶음의 실행 수
 * @param estimatedMicros 환산 금액의 합. 통화 단위의 100만분의 1
 * @param actualMicros 실제 청구액의 합. 구독 경로만 있으면 null
 * @param inputTokens 입력 토큰의 합
 * @param outputTokens 출력 토큰의 합
 * @param avgContextChars 문맥 글자 수 평균
 * @param firstSeenAt 그 묶음에서 가장 이른 시작 시각
 * @param lastSeenAt 그 묶음에서 가장 늦은 시작 시각
 */
public record CostByAgent(
        Long agentId,
        String agentCode,
        String agentName,
        Long executions,
        Long estimatedMicros,
        Long actualMicros,
        Long inputTokens,
        Long outputTokens,
        Double avgContextChars,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
