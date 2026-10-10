package com.bifos.assistant.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * 에이전트와 그 에이전트가 받는 Memory collection 의 key 다.
 */
@Embeddable
public record AgentMemoryCollectionId(
    @Column(name = "agent_id", nullable = false) Long agentId,

    @Column(name = "collection", nullable = false, length = 64) String collection)
    implements
        Serializable {
}
