package com.bifos.assistant.memory.domain.entity;

import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
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
 * 기억 두 개 사이의 연결 한 줄이다(ADR-20261015 / memory-link).
 *
 * <p>「대체됨」 은 {@code memory} 의 상태가 아니라 {@code ACTIVE} 인 {@code SUPERSEDES} 줄이 그 항목을 가리키는지로만 판정한다. 칸의 뜻은
 * {@code backend/docs/data-schema.md} 의 「memory_link」 가 갖는다.
 */
@Entity
@Table(name = "memory_link")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_memory_id", nullable = false)
    private Long fromMemoryId;

    @Column(name = "to_memory_id", nullable = false)
    private Long toMemoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryLinkType type;

    /** {@code RELATED} 연결의 뜻이다. Control Plane 은 이 글로 판정하지 않는다. */
    @Column(length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryLinkOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryLinkStatus status;

    /** 코드가 찾은 연결의 유사도다. 사람과 에이전트가 맺은 연결은 null 이다. */
    @Column
    private Double score;

    @Column(name = "created_by_execution_id")
    private Long createdByExecutionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;


    /** 새 항목이 옛 항목을 대신한다는 제안이다. 사람이 받아들이기 전에는 아무것도 바꾸지 않는다. */
    public static MemoryLink proposeSupersede(
        Long newMemoryId, Long oldMemoryId, MemoryLinkOrigin origin, Long executionId, Instant now) {
        MemoryLink link = new MemoryLink();
        link.fromMemoryId = newMemoryId;
        link.toMemoryId = oldMemoryId;
        link.type = MemoryLinkType.SUPERSEDES;
        link.origin = origin;
        link.status = MemoryLinkStatus.PROPOSED;
        link.createdByExecutionId = executionId;
        link.createdAt = now;
        link.updatedAt = now;
        return link;
    }


    /** 두 항목의 관련 연결이다. 방향이 없으므로 번호가 작은 쪽을 {@code from} 에 둔다. 바로 효력이 있다. */
    public static MemoryLink related(
        Long a, Long b, String label, MemoryLinkOrigin origin, Double score, Long executionId, Instant now) {
        MemoryLink link = new MemoryLink();
        link.fromMemoryId = Math.min(a, b);
        link.toMemoryId = Math.max(a, b);
        link.type = MemoryLinkType.RELATED;
        link.label = label;
        link.origin = origin;
        link.status = MemoryLinkStatus.ACTIVE;
        link.score = score;
        link.createdByExecutionId = executionId;
        link.createdAt = now;
        link.updatedAt = now;
        return link;
    }


    /** 사람이 대체 제안을 받아들였다. */
    public void accept(Instant now) {
        status = MemoryLinkStatus.ACTIVE;
        updatedAt = now;
    }


    /** 끊거나 거절했다. 줄은 남긴다. */
    public void revoke(Instant now) {
        status = MemoryLinkStatus.REVOKED;
        revokedAt = now;
        updatedAt = now;
    }


    /**
     * 끊은 줄을 같은 쌍의 새 연결로 다시 쓴다. 대체는 다시 제안이 되고 관련은 바로 효력이 있다.
     *
     * <p>누가 만들었는지와 실행 번호, label, 유사도는 다시 맺은 쪽의 값으로 바꾼다.
     */
    public void reopen(String label, MemoryLinkOrigin origin, Double score, Long executionId, Instant now) {
        this.status = type == MemoryLinkType.SUPERSEDES ? MemoryLinkStatus.PROPOSED : MemoryLinkStatus.ACTIVE;
        this.label = label;
        this.origin = origin;
        this.score = score;
        this.createdByExecutionId = executionId;
        this.revokedAt = null;
        this.updatedAt = now;
    }


    public boolean revoked() {
        return status == MemoryLinkStatus.REVOKED;
    }
}
