package com.bifos.assistant.agent.domain;

import com.bifos.assistant.agent.domain.type.AgentMemoryCollectionChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 에이전트가 받는 collection 한 줄이 바뀐 기록이다(ADR-20261008 / agent-memory-grants-admin).
 *
 * <p>사람이 바꾼 것만 남기고 지우지 않는다. 칸의 뜻은 {@code docs/backend/schema/memory.md} 의
 * 「agent_memory_collection_change」 가 갖는다.
 */
@Entity
@Table(name = "agent_memory_collection_change")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMemoryCollectionChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(nullable = false, length = 64)
    private String collection;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private AgentMemoryCollectionChangeType changeType;

    /** 붙임과 민감 허용 바꿈은 바꾼 뒤의 값, 뗌은 떼기 전의 값이다. */
    @Column(name = "allow_sensitive", nullable = false)
    private boolean allowSensitive;

    /** 바꾼 관리자다. 사용자를 지워도 기록은 남으므로 외래 키를 걸지 않는다. */
    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public static AgentMemoryCollectionChange of(
            Long agentId,
            String collection,
            AgentMemoryCollectionChangeType type,
            boolean allowSensitive,
            Long changedByUserId,
            Instant now) {
        AgentMemoryCollectionChange change = new AgentMemoryCollectionChange();
        change.agentId = agentId;
        change.collection = collection;
        change.changeType = type;
        change.allowSensitive = allowSensitive;
        change.changedByUserId = changedByUserId;
        change.changedAt = now;
        return change;
    }
}
