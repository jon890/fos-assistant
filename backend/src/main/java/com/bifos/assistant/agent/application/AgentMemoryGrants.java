package com.bifos.assistant.agent.application;

import java.util.Set;

/**
 * 에이전트 하나가 받는 Memory collection 이다(ADR-053).
 *
 * @param collections 받는 collection 의 key
 * @param sensitiveCollections 그 가운데 민감 항목까지 받는 collection 의 key
 */
public record AgentMemoryGrants(Set<String> collections, Set<String> sensitiveCollections) {

    public AgentMemoryGrants {
        collections = Set.copyOf(collections);
        sensitiveCollections = Set.copyOf(sensitiveCollections);
    }

    public static AgentMemoryGrants none() {
        return new AgentMemoryGrants(Set.of(), Set.of());
    }
}
