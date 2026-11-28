package com.bifos.assistant.connector.connection.application.dto;

import java.util.List;

/**
 * 에이전트 하나의 연결 목록이다.
 *
 * @param blockedReason 이 에이전트에 연결을 붙일 수 없는 까닭. 붙일 수 있으면 null 이다.
 * {@link #AGENT_NOT_PRIVATE}, {@link #PRIMARY_AGENT} 다
 */
public record AgentConnectionsView(List<AgentConnectionView> connections, String blockedReason) {

    /** 그룹에 공개된 에이전트다. 연결은 비공개 에이전트에만 붙인다. */
    public static final String AGENT_NOT_PRIVATE = "AGENT_NOT_PRIVATE";

    /** 요청자의 기본 비서다. 연결은 전문 에이전트에만 붙이고 이미 붙은 연결은 떼기만 한다(docs/features/assistant-hub/spec.md 의 REQ9). */
    public static final String PRIMARY_AGENT = "PRIMARY_AGENT";


    public AgentConnectionsView {
        connections = List.copyOf(connections);
    }
}
