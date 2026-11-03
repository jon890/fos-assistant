package com.bifos.assistant.agent.domain;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 에이전트가 받는 Memory collection 하나다(ADR-053).
 *
 * <p>줄이 있는 collection 의 Memory 만 그 에이전트의 실행에 실린다. 줄이 하나도 없으면 아무것도 실리지 않는다.
 * 커넥터 에이전트는 줄이 있어도 받지 않는다(ADR-045).
 */
@Entity
@Table(name = "agent_memory_collection")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMemoryCollection extends CreatedAtEntity {

    /** 새 에이전트가 처음부터 받는 collection 이다. */
    public static final String DEFAULT_COLLECTION = "core";

    @EmbeddedId
    private AgentMemoryCollectionId id;


    private AgentMemoryCollection(Long agentId, String collection) {
        this.id = new AgentMemoryCollectionId(agentId, collection);
    }


    public static AgentMemoryCollection of(Long agentId, String collection) {
        return new AgentMemoryCollection(agentId, collection);
    }


    public String collection() {
        return id.collection();
    }

}
