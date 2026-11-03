package com.bifos.assistant.agent.application.dto;

import java.util.Set;

/**
 * 에이전트 하나가 받는 Memory collection 이다(ADR-053).
 *
 * @param collections 받는 collection 의 key
 */
public record AgentMemoryGrants(Set<String> collections) {

    public AgentMemoryGrants {
        collections = Set.copyOf(collections);
    }


    public static AgentMemoryGrants none() {
        return new AgentMemoryGrants(Set.of());
    }
}
