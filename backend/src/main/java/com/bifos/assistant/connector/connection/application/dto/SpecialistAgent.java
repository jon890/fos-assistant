package com.bifos.assistant.connector.connection.application.dto;

import java.util.List;

/**
 * 전문 에이전트를 만든 결과다(docs/features/agent-team/design.md 의 「PR3 마법사」).
 *
 * @param connection 새 에이전트에서 본 그 연결. 붙인 직후라 대개 {@code PENDING} 이다
 * @param detachedFrom 그 연결을 뗀 다른 에이전트들. 떼지 않았으면 빈 목록
 */
public record SpecialistAgent(
    String agentCode, String agentName, AgentConnectionView connection, List<BoundAgentSummary> detachedFrom) {

    public SpecialistAgent {
        detachedFrom = List.copyOf(detachedFrom);
    }
}
