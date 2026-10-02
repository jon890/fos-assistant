package com.bifos.assistant.usage.domain;

import java.time.Instant;

/**
 * 합계에 더할 native 자식 원장 한 줄을 부모 실행의 축 값과 함께 읽은 것이다.
 *
 * <p>자식은 부모 실행의 에이전트, 시작 시각, 지문에 붙고 모델 축에서는 자기 provider 와 모델에 붙는다.
 * 근거는 ADR-060 에 있다.
 *
 * @param agentId 부모 실행의 에이전트 번호
 * @param parentStartedAt 부모 실행의 시작 시각. 날짜 축이 이 값으로 날짜를 뽑는다
 * @param runtimeFingerprint 부모 실행의 문맥 지문. 없으면 null
 * @param status 원장 줄의 상태. {@code WAITING}, {@code DONE}, {@code EXPIRED} 중 하나
 * @param provider 자식이 돈 provider. 읽지 못했으면 null
 * @param inputTokens cache 를 뺀 일반 입력 토큰
 */
public record SubagentLedgerRow(
        Long agentId,
        Instant parentStartedAt,
        String runtimeFingerprint,
        String status,
        String provider,
        String model,
        Long inputTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long outputTokens,
        Long estimatedCostMicros,
        Long actualCostMicros) {

    /** 종료를 확인해 사용량을 적은 줄인가. */
    public boolean recorded() {
        return "DONE".equals(status);
    }

    /** 금액까지 적힌 줄인가. 이 줄만 금액 합계에 더한다. */
    public boolean priced() {
        return recorded() && estimatedCostMicros != null;
    }

    /** 실행 줄의 입력 토큰처럼 cache 를 포함한 입력 토큰이다. 셋을 모두 모르면 null 이다. */
    public Long inclusiveInputTokens() {
        if (inputTokens == null && cacheReadTokens == null && cacheWriteTokens == null) {
            return null;
        }
        return orZero(inputTokens) + orZero(cacheReadTokens) + orZero(cacheWriteTokens);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
