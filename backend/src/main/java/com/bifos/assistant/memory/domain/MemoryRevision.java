package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.data.domain.Persistable;

/**
 * 지금 값에서 물러난 판 하나다(ADR-052).
 *
 * <p>고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다. 지운 뒤에는 {@code memory} 에 줄이 없으므로 누가 볼 수
 * 있는지를 정하는 범위와 주인을 이 줄이 함께 갖는다.
 */
@Entity
@Table(name = "memory_revision")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryRevision implements Persistable<MemoryRevisionId> {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private MemoryEntryType entryType;

    @Column(name = "document_key", length = 128)
    private String documentKey;

    /** 그때의 승인 상태다. 지운 항목이 제안이었는지 받아들인 항목이었는지 남긴다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryStatus status;

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

    /** 데이터베이스에 있는 판이다. 읽어 왔거나 방금 넣었을 때 참이 된다. */
    @Transient
    private boolean persisted;

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
        revision.entryType = memory.entryType();
        revision.documentKey = memory.documentKey();
        revision.status = memory.status();
        revision.title = memory.title();
        revision.content = memory.content();
        revision.contentKeyId = memory.contentKeyId();
        revision.retrieval = memory.retrieval();
        revision.sensitivity = memory.sensitivity();
        revision.changedByUserId = changedByUserId;
        revision.reason = reason;
        revision.changedAt = at;
        return revision;
    }

    /** 암호문이 누구의 것인지 적은 글이다. {@link Memory#contentBinding()} 과 같은 규칙이다. */
    public String contentBinding() {
        return scope == MemoryScope.USER ? "USER:" + ownerUserId : "GROUP:" + groupId;
    }

    /**
     * 남긴 판의 뜻은 바뀌지 않는다. 평문으로 남은 민감 판의 저장 모양만 바꾼다(ADR-055).
     *
     * @throws IllegalStateException 이미 암호문일 때
     */
    public void sealInPlace(StoredContent body) {
        if (contentKeyId != null) {
            throw new IllegalStateException("memory revision content is already sealed");
        }
        this.content = body.content();
        this.contentKeyId = body.keyId();
    }

    /** Spring Data 의 {@link Persistable} 이 요구하는 이름이다. 값은 Lombok 이 만든 {@code id()} 와 같다. */
    @Override
    public MemoryRevisionId getId() {
        return id();
    }

    /**
     * 판은 늘 새 줄로 넣는다. 번호를 직접 정하므로 이것이 없으면 저장이 같은 번호의 줄을 조용히 덮어쓴다.
     *
     * <p>같은 번호의 판이 이미 있으면 기본 키 위반으로 실패한다. 남긴 판은 바뀌지 않는다.
     */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
