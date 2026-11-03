package com.bifos.assistant.memory.domain.entity;

import com.bifos.assistant.memory.domain.MemoryText;
import com.bifos.assistant.memory.domain.MemoryTextOpener;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.StoredMemoryText;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
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
import java.util.Optional;
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
 *
 * <p>제목과 본문은 원래 기억과 같은 데이터 key, 같은 AAD 로 암호화한다. 그래서 지금 줄의 암호문을 그대로 옮긴다
 * (ADR-20261103 / memory-encryption). 내는 곳은 {@link #title()} 과 {@link #content()} 를 쓴다.
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

    /** 저장된 제목이다. {@link #dataKeyId} 가 있으면 암호문이다. */
    @Column(name = "title", nullable = false, length = 1200)
    private String storedTitle;

    /** 저장된 본문이다. {@link #dataKeyId} 가 있으면 암호문이다. */
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String storedContent;

    /** 제목과 본문을 암호화한 데이터 key({@code user_data_key.id}). 비어 있으면 평문이다. */
    @Column(name = "data_key_id")
    private Long dataKeyId;

    @Transient
    @Getter(AccessLevel.NONE)
    private MemoryText plain;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean unreadable;

    @Transient
    @Getter(AccessLevel.NONE)
    private MemoryTextOpener opener;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryRetrieval retrieval;

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
        revision.storedTitle = memory.storedTitle();
        revision.storedContent = memory.storedContent();
        revision.dataKeyId = memory.dataKeyId();
        revision.plain = memory.readable() ? new MemoryText(memory.title(), memory.content()) : null;
        revision.unreadable = !memory.readable();
        revision.retrieval = memory.retrieval();
        revision.changedByUserId = changedByUserId;
        revision.reason = reason;
        revision.changedAt = at;
        return revision;
    }


    /** 제목이다. 암호문이면 처음 부를 때 푼다. 풀지 못하면 {@link Memory#UNREADABLE_TITLE} 이다. */
    public String title() {
        return opened().map(MemoryText::title).orElse(Memory.UNREADABLE_TITLE);
    }


    /** 본문이다. 암호문이면 처음 부를 때 푼다. 풀지 못하면 빈 글이다. */
    public String content() {
        return opened().map(MemoryText::content).orElse("");
    }


    /** 제목과 본문을 풀었는가. */
    public boolean readable() {
        return opened().isPresent();
    }


    /** 저장된 글과 key, 주인이다. 원래 기억의 번호로 AAD 를 만든다. */
    public StoredMemoryText stored() {
        return new StoredMemoryText(
            id.memoryId(), scope, scope == MemoryScope.USER ? ownerUserId : groupId, storedTitle, storedContent, dataKeyId);
    }


    /** 읽어 올 때 복호화 도구를 붙인다. */
    public void attachOpener(MemoryTextOpener opener) {
        this.opener = opener;
    }


    /** 남긴 revision 의 뜻은 바꾸지 않고 저장 모양만 주인 데이터 key 의 암호문으로 바꾼다. */
    public void seal(SealedMemoryText sealed) {
        opened();
        this.storedTitle = sealed.title();
        this.storedContent = sealed.content();
        this.dataKeyId = sealed.dataKeyId();
    }


    private Optional<MemoryText> opened() {
        if (plain == null && !unreadable) {
            Optional<MemoryText> read = dataKeyId != null
                ? (opener == null ? Optional.empty() : opener.open(stored()))
                : Optional.of(new MemoryText(storedTitle, storedContent));
            plain = read.orElse(null);
            unreadable = read.isEmpty();
        }
        return Optional.ofNullable(plain);
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
