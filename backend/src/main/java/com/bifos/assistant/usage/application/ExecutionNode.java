package com.bifos.assistant.usage.application;

import java.time.Instant;
import java.util.List;

/**
 * 나무의 노드 하나다. 실행 한 줄과 그 실행의 사건, 그리고 그 실행이 부른 실행들을 담는다.
 *
 * <p>자식이 없으면 {@code children} 은 빈 목록이다. {@code null} 을 내지 않는다.
 *
 * @param truncated <b>이 노드 아래</b>를 잘랐다. 화면이 그 자리에 한 줄을 적는다. 나무 전체를 어딘가
 *     잘랐는지는 {@link ExecutionTree#truncated()} 가 따로 알린다
 */
public record ExecutionNode(
        boolean truncated,
        Long executionId,
        String agentCode,
        String agentName,
        String status,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long estimatedCostMicros,
        Long latencyMs,
        Instant startedAt,
        List<ExecutionEventView> events,
        List<ExecutionNode> children) {
}
