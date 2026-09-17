package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "memory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Memory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryScope scope;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "family_id")
    private Long familyId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "always_inject", nullable = false)
    private boolean alwaysInject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryStatus status;

    @Column(name = "proposed_by_execution_id")
    private Long proposedByExecutionId;

    @Column(name = "proposal_dedup_key", unique = true, length = 64)
    private String proposalDedupKey;

    @Column(name = "accepted_by_user_id")
    private Long acceptedByUserId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Memory(MemoryScope scope, Long ownerUserId, Long familyId, String title, String content,
            boolean alwaysInject, MemoryStatus status, Long proposedByExecutionId) {
        this.scope = scope;
        this.ownerUserId = ownerUserId;
        this.familyId = familyId;
        this.title = title;
        this.content = content;
        this.alwaysInject = alwaysInject;
        this.status = status;
        this.proposedByExecutionId = proposedByExecutionId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Memory accepted(MemoryScope scope, Long ownerUserId, Long familyId, String title,
            String content, boolean alwaysInject, Long acceptedByUserId) {
        Memory memory = new Memory(scope, ownerUserId, familyId, title, content, alwaysInject,
                MemoryStatus.ACCEPTED, null);
        memory.acceptedByUserId = acceptedByUserId;
        memory.acceptedAt = memory.createdAt;
        return memory;
    }

    public static Memory proposedUser(Long ownerUserId, String title, String content,
            Long proposedByExecutionId, String proposalDedupKey) {
        Memory memory = new Memory(MemoryScope.USER, ownerUserId, null, title, content, false,
                MemoryStatus.PROPOSED, proposedByExecutionId);
        memory.proposalDedupKey = proposalDedupKey;
        return memory;
    }

    /** 사람이 받아들인다. 이때부터 주입 대상이 된다. */
    public void accept(Long userId, Instant at) {
        status = MemoryStatus.ACCEPTED;
        acceptedByUserId = userId;
        acceptedAt = at;
        updatedAt = at;
    }

    /** 사람이 물린다. 주입되지 않는다. */
    public void reject() {
        status = MemoryStatus.REJECTED;
        updatedAt = Instant.now();
    }

    public void updateContentAndInjection(String content, boolean alwaysInject) {
        this.content = content;
        this.alwaysInject = alwaysInject;
        this.updatedAt = Instant.now();
    }

    /** USER 는 주인만, FAMILY 는 같은 가구의 구성원이 본다. */
    public boolean isReadableBy(Long userId, Long familyId) {
        return scope == MemoryScope.USER
                ? Objects.equals(ownerUserId, userId)
                : Objects.equals(this.familyId, familyId);
    }

    public Long id() { return id; }
    public MemoryScope scope() { return scope; }
    public Long ownerUserId() { return ownerUserId; }
    public Long familyId() { return familyId; }
    public String title() { return title; }
    public String content() { return content; }
    public boolean alwaysInject() { return alwaysInject; }
    public MemoryStatus status() { return status; }
    public Long proposedByExecutionId() { return proposedByExecutionId; }
    public String proposalDedupKey() { return proposalDedupKey; }
    public Long acceptedByUserId() { return acceptedByUserId; }
    public Instant acceptedAt() { return acceptedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
