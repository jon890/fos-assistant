package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 지금 값에서 물러난 판 하나다(ADR-051).
 *
 * <p>고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다. 지운 뒤에는 {@code memory} 에 줄이 없으므로 누가 볼 수
 * 있는지를 정하는 범위와 주인을 이 줄이 함께 갖는다.
 */
@Entity
@Table(name = "memory_revision")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryRevision {

    @EmbeddedId
    private MemoryRevisionId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private MemoryChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryScope scope;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "group_id")
    private Long groupId;

    @Column(nullable = false, length = 64)
    private String collection;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryRetrieval retrieval;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemorySensitivity sensitivity;

    /** 이 판을 물러나게 한 사람이다. */
    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Column(length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /**
     * 항목의 지금 값을 판으로 옮긴다. 항목을 고치거나 지우기 직전에 부른다.
     *
     * @param reason 바꾼 까닭. 적지 않으면 null 이다
     */
    public static MemoryRevision of(
            Memory memory, MemoryChangeType changeType, Long changedByUserId, String reason, Instant at) {
        MemoryRevision revision = new MemoryRevision();
        revision.id = new MemoryRevisionId(memory.id(), memory.revision());
        revision.changeType = changeType;
        revision.scope = memory.scope();
        revision.ownerUserId = memory.ownerUserId();
        revision.groupId = memory.groupId();
        revision.collection = memory.collection();
        revision.title = memory.title();
        revision.content = memory.content();
        revision.retrieval = memory.retrieval();
        revision.sensitivity = memory.sensitivity();
        revision.changedByUserId = changedByUserId;
        revision.reason = reason;
        revision.changedAt = at;
        return revision;
    }
}
