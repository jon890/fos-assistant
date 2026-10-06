package com.bifos.assistant.connector.application.model;

import java.util.List;

/**
 * 에이전트 하나의 연결 목록이다.
 *
 * @param blockedReason 이 에이전트에 연결을 붙일 수 없는 까닭. 붙일 수 있으면 null 이다.
 *     {@link #AGENT_NOT_PRIVATE} 나 {@link #LEGACY_AGENT} 다
 */
public record AgentConnectionsView(List<AgentConnectionView> connections, String blockedReason) {

    /** 그룹에 공개된 에이전트다. 연결은 비공개 에이전트에만 붙인다. */
    public static final String AGENT_NOT_PRIVATE = "AGENT_NOT_PRIVATE";

    /** 커넥터마다 만들었던 예전 방식의 연결 에이전트다. 그 에이전트를 지우고 원래 에이전트에 붙인다. */
    public static final String LEGACY_AGENT = "LEGACY_AGENT";

    public AgentConnectionsView {
        connections = List.copyOf(connections);
    }
}
