package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Memory 한 줄이다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의 「memory」 가 갖는다(ADR-052).
 *
 * <p>{@code alwaysInject} 는 {@code retrieval} 로 옮겨 가는 옛 칸이다. 한 배포 동안 남기고 쓸 때마다 {@code retrieval}
 * 과 맞춘다. 읽을 때는 {@code retrieval} 만 본다.
 *
 * <p>{@code content} 는 저장된 글이다. 민감 줄이면 암호문이고 {@code contentKeyId} 가 그 key 를 적는다. 평문은
 * {@code MemoryService.contentOf} 로 읽는다(ADR-055).
 */
@Entity
@Table(name = "memory")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Memory {

    /** 따로 정하지 않은 항목이 가는 collection 이다. 기존 Memory 가 모두 여기 있다. */
    public static final String DEFAULT_COLLECTION = "core";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryScope scope;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "group_id")
    private Long groupId;

    @Column(nullable = false, length = 64)
    private String collection;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private MemoryEntryType entryType;

    @Column(name = "document_key", length = 128)
    private String documentKey;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 본문을 암호화한 key 의 id 다. 비어 있으면 {@code content} 는 평문이다. */
    @Column(name = "content_key_id", length = 32)
    private String contentKeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryRetrieval retrieval;

    @Column(name = "always_inject", nullable = false)
    private boolean alwaysInject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemorySensitivity sensitivity;

    /** 지금 값의 판 번호다. 1 에서 시작하고 고칠 때마다 1 씩 는다. */
    @Column(nullable = false)
    private int revision;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    @Column(name = "source_date")
    private LocalDate sourceDate;

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

    private Memory(
            MemoryScope scope,
            Long ownerUserId,
            Long groupId,
            String title,
            StoredContent body,
            MemoryPlacement placement,
            MemoryStatus status,
            Long proposedByExecutionId,
            Instant now) {
        this.scope = scope;
        this.ownerUserId = ownerUserId;
        this.groupId = groupId;
        this.title = title;
        this.content = body.content();
        this.contentKeyId = body.keyId();
        this.collection = placement.collection();
        this.entryType = MemoryEntryType.MEMORY;
        this.sensitivity = placement.sensitivity();
        place(placement.retrieval());
        this.revision = 1;
        this.status = status;
        this.proposedByExecutionId = proposedByExecutionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Memory accepted(
            MemoryScope scope,
            Long ownerUserId,
            Long groupId,
            String title,
            StoredContent body,
            MemoryPlacement placement,
            Long acceptedByUserId,
            Instant now) {
        Memory memory =
                new Memory(scope, ownerUserId, groupId, title, body, placement, MemoryStatus.ACCEPTED, null, now);
        memory.acceptedByUserId = acceptedByUserId;
        memory.acceptedAt = now;
        return memory;
    }

    /**
     * 사용자가 직접 쓴 문서다. 곧 ACCEPTED 이고 꺼내는 방식은 SEARCH 로 고정한다. 민감 문서가 항상 층으로 올라갈 길을
     * 만들지 않는다(ADR-057).
     */
    public static Memory document(
            Long ownerUserId,
            String collection,
            String documentKey,
            String title,
            StoredContent body,
            MemorySensitivity sensitivity,
            Instant now) {
        Memory memory = accepted(
                MemoryScope.USER,
                ownerUserId,
                null,
                title,
                body,
                new MemoryPlacement(collection, MemoryRetrieval.SEARCH, sensitivity),
                ownerUserId,
                now);
        memory.entryType = MemoryEntryType.DOCUMENT;
        memory.documentKey = documentKey;
        return memory;
    }

    public static Memory proposedUser(
            Long ownerUserId,
            String title,
            String content,
            Long proposedByExecutionId,
            String proposalDedupKey,
            Instant now) {
        Memory memory = new Memory(
                MemoryScope.USER,
                ownerUserId,
                null,
                title,
                StoredContent.plain(content),
                MemoryPlacement.core(MemoryRetrieval.SEARCH),
                MemoryStatus.PROPOSED,
                proposedByExecutionId,
                now);
        memory.proposalDedupKey = proposalDedupKey;
        return memory;
    }

    /**
     * 에이전트가 {@code memory_remember} 로 제안한 개인 항목이다(ADR-20261007 / memory-remember). 민감 항목이면 중복 키를 비운다. 키가 본문의 해시라
     * 평문 칸에 지문이 남기 때문이다.
     */
    public static Memory proposed(
            Long ownerUserId,
            String title,
            StoredContent body,
            MemoryPlacement placement,
            Long proposedByExecutionId,
            String proposalDedupKey,
            Instant now) {
        Memory memory = new Memory(
                MemoryScope.USER,
                ownerUserId,
                null,
                title,
                body,
                placement,
                MemoryStatus.PROPOSED,
                proposedByExecutionId,
                now);
        memory.proposalDedupKey = placement.sensitivity() == MemorySensitivity.SENSITIVE ? null : proposalDedupKey;
        return memory;
    }

    /**
     * 사용자가 대화에서 직접 말해 바로 저장한 개인 항목이다(ADR-20261007 / memory-remember). 그 말을 승인으로 보아 곧 ACCEPTED 이고 승인한 사람은
     * 주인이다. 민감 항목은 이 길로 오지 않는다.
     */
    public static Memory remembered(
            Long ownerUserId,
            String title,
            StoredContent body,
            MemoryPlacement placement,
            Long proposedByExecutionId,
            String proposalDedupKey,
            Instant now) {
        if (placement.sensitivity() == MemorySensitivity.SENSITIVE) {
            throw new IllegalArgumentException("a sensitive memory cannot be remembered without review");
        }
        Memory memory = accepted(MemoryScope.USER, ownerUserId, null, title, body, placement, ownerUserId, now);
        memory.proposedByExecutionId = proposedByExecutionId;
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
    public void reject(Instant at) {
        status = MemoryStatus.REJECTED;
        updatedAt = at;
    }

    /** 기존 제안을 받아들인 기록을 되돌린다. 승인 정보도 지워 다시 주입되지 않게 한다. */
    public void restoreProposal(Instant at) {
        status = MemoryStatus.PROPOSED;
        acceptedByUserId = null;
        acceptedAt = null;
        updatedAt = at;
    }

    /**
     * 본문과 꺼내는 방식과 민감도를 고치고 판을 하나 올린다.
     *
     * <p>고치기 전의 값은 부르는 쪽이 {@link MemoryRevision#of} 로 먼저 남긴다.
     *
     * @throws IllegalArgumentException 민감 항목을 항상 싣게 하려 할 때. {@link MemoryPlacement#allows} 로 먼저 본다
     */
    public void revise(
            StoredContent body,
            MemoryRetrieval retrieval,
            MemorySensitivity sensitivity,
            String updatedProposalDedupKey,
            Instant at) {
        if (!MemoryPlacement.allows(retrieval, sensitivity)) {
            throw new IllegalArgumentException("a sensitive memory cannot always be injected");
        }
        this.content = body.content();
        this.contentKeyId = body.keyId();
        this.sensitivity = sensitivity;
        place(retrieval);
        if (proposalDedupKey != null) {
            proposalDedupKey = updatedProposalDedupKey;
        }
        this.revision += 1;
        this.updatedAt = at;
    }

    /** 본문이 암호문으로 저장돼 있는가. */
    public boolean sealed() {
        return contentKeyId != null;
    }

    /** 암호문이 누구의 것인지 적은 글이다. 암호화와 풀기가 같은 값을 써야 한다(ADR-055). */
    public String contentBinding() {
        return scope == MemoryScope.USER ? "USER:" + ownerUserId : "GROUP:" + groupId;
    }

    /**
     * 평문으로 남은 본문의 저장 모양만 암호문으로 바꾼다. 판 번호와 갱신 시각은 건드리지 않는다.
     *
     * @throws IllegalStateException 이미 암호문일 때
     */
    public void sealInPlace(StoredContent body) {
        if (sealed()) {
            throw new IllegalStateException("memory content is already sealed");
        }
        this.content = body.content();
        this.contentKeyId = body.keyId();
    }

    /** USER 는 주인만, GROUP 은 같은 그룹의 사용자가 본다. 그룹이 없는 사용자는 GROUP 항목을 보지 못한다. */
    public boolean isReadableBy(Long userId, Long groupId) {
        return scope == MemoryScope.USER
                ? userId != null && Objects.equals(ownerUserId, userId)
                : groupId != null && Objects.equals(this.groupId, groupId);
    }

    /** 꺼내는 방식을 적고 옛 칸을 같은 뜻으로 맞춘다. */
    private void place(MemoryRetrieval retrieval) {
        this.retrieval = retrieval;
        this.alwaysInject = retrieval == MemoryRetrieval.ALWAYS;
    }
}
