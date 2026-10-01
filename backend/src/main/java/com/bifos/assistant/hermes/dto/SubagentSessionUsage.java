package com.bifos.assistant.hermes.dto;

/** 종료한 자식 session의 최종 사용량이다. 종료는 성공을 뜻하지 않는다. */
public record SubagentSessionUsage(
        String id, String source, String parentSessionId, String model,
        Double startedAt, Double endedAt, Long inputTokens, Long outputTokens,
        Long cacheReadTokens, Long cacheWriteTokens) {

    /** Runs usage와 같이 캐시를 포함한 입력 토큰을 쓴다. 필요한 값을 모르면 비운다. */
    public Long inclusiveInputTokens() {
        if (inputTokens == null || cacheReadTokens == null || cacheWriteTokens == null) {
            return null;
        }
        return Math.addExact(Math.addExact(inputTokens, cacheReadTokens), cacheWriteTokens);
    }

    public Long durationMs() {
        if (startedAt == null || endedAt == null || endedAt < startedAt) {
            return null;
        }
        return Math.round((endedAt - startedAt) * 1000);
    }
}
