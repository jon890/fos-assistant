package com.bifos.assistant.usage.application;

import java.time.Instant;
import java.util.List;

/**
 * 트리의 노드 하나다. 실행 한 줄과 그 실행의 사건, 그리고 그 실행이 부른 실행들을 담는다.
 *
 * <p>자식이 없으면 {@code children} 은 빈 목록이다. {@code null} 을 내지 않는다.
 *
 * @param truncated <b>이 노드 아래</b>를 잘랐다. 화면이 그 자리에 한 줄을 적는다. 트리 전체를 어딘가
 *     잘랐는지는 {@link ExecutionTree#truncated()} 가 따로 알린다
 * @param contextSources 이 실행의 문맥에 실은 항목의 참조. 내부 값이라 관리자에게만 싣고 그 밖에는 null 이다
 */
public record ExecutionNode(
        boolean truncated,
        Long executionId,
        String agentCode,
        String agentName,
        String status,
        String provider,
        String model,
        String reasoningEffort,
        String reasoningEffortSource,
        String modelTier,
        Long inputTokens,
        Long cachedInputTokens,
        Long outputTokens,
        Long totalTokens,
        Long estimatedCostMicros,
        Long latencyMs,
        Instant requestReceivedAt,
        Instant submittedAt,
        Instant firstDeltaAt,
        Instant startedAt,
        Instant finishedAt,
        List<ContextSourceRef> contextSources,
        List<ExecutionEventView> events,
        List<ExecutionNode> children) {}
