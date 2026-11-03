package com.bifos.assistant.memory.domain.entity;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.MemoryText;
import com.bifos.assistant.memory.domain.MemoryTextOpener;
import com.bifos.assistant.memory.domain.SealedMemoryText;
import com.bifos.assistant.memory.domain.StoredMemoryText;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.ColumnDefault;

/**
 * Memory 한 줄이다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의 「memory」 가 갖는다(ADR-052).
 *
 * <p>{@code alwaysInject} 는 {@code retrieval} 로 옮겨 가는 옛 칸이다. 한 배포 동안 남기고 쓸 때마다 {@code retrieval}
 * 과 맞춘다. 읽을 때는 {@code retrieval} 만 본다.
 *
 * <p>{@code storedTitle} 과 {@code storedContent} 는 저장된 글이다. {@code dataKeyId} 가 있으면 주인 데이터 key 의
 * 암호문이다(ADR-20261103 / memory-encryption). 비어 있으면 암호화가 꺼진 설치의 평문이다.
 * 제목과 본문을 내는 곳은 {@link #title()} 과 {@link #content()} 를 쓴다. 처음 부를 때 읽어 올 때 붙인 복호화 도구로 푼다.
 */
@Entity
@Table(name = "memory")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Memory extends CreatedAtEntity {

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

    /** 풀지 못한 줄의 제목 자리에 내는 글이다. */
    public static final String UNREADABLE_TITLE = "읽지 못함";

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

    @Column(name = "always_inject", nullable = false)
    private boolean alwaysInject;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 이 항목을 대신한 새 항목의 번호다(ADR-20261102 / table-reduce-r2). 비어 있지 않으면 어디에도 실리지 않는다. 엔티티는 이 칸을 읽기만 하고
     * 쓰지 않는다. 칸을 비우는 일은 저장소의 전용 쿼리가 한다.
     */
    @Column(name = "superseded_by_memory_id", insertable = false, updatable = false)
    private Long supersededByMemoryId;

    /**
     * 정비 루프가 이 항목을 자동 보관해도 되는가(ADR-20261015 / memory-maintenance). 기본은 거짓이다. 거짓이면 변하지 않는 사실이거나 모르는
     * 것으로 보고 자동 보관하지 않는다. 항목의 종류를 나누는 칸이 아니다.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean archivable;


    private Memory(
        MemoryScope scope,
        Long ownerUserId,
        Long groupId,
        String title,
        String content,
        MemoryPlacement placement,
        MemoryStatus status,
        Long proposedByExecutionId,
        Instant now) {
        this.scope = scope;
        this.ownerUserId = ownerUserId;
        this.groupId = groupId;
        this.storedTitle = title;
        this.storedContent = content;
        this.plain = new MemoryText(title, content);
        this.collection = placement.collection();
        this.entryType = MemoryEntryType.MEMORY;
        place(placement.retrieval());
        this.revision = 1;
        this.status = status;
        this.proposedByExecutionId = proposedByExecutionId;
        this.updatedAt = now;
    }


    public static Memory accepted(
        MemoryScope scope,
        Long ownerUserId,
        Long groupId,
        String title,
        String content,
        MemoryPlacement placement,
        Long acceptedByUserId,
        Instant now) {
        Memory memory = new Memory(scope, ownerUserId, groupId, title, content, placement, MemoryStatus.ACCEPTED, null, now);
        memory.acceptedByUserId = acceptedByUserId;
        memory.acceptedAt = now;
        return memory;
    }


    /** 사용자가 직접 쓴 문서다. 곧 ACCEPTED 이고 꺼내는 방식은 SEARCH 로 고정한다(ADR-057). */
    public static Memory document(
        Long ownerUserId,
        String collection,
        String documentKey,
        String title,
        String content,
        Instant now) {
        Memory memory = accepted(
            MemoryScope.USER,
            ownerUserId,
            null,
            title,
            content,
            new MemoryPlacement(collection, MemoryRetrieval.SEARCH),
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
            content,
            MemoryPlacement.core(MemoryRetrieval.SEARCH),
            MemoryStatus.PROPOSED,
            proposedByExecutionId,
            now);
        memory.proposalDedupKey = proposalDedupKey;
        return memory;
    }


    /** 에이전트가 {@code memory_remember} 로 제안한 개인 항목이다(ADR-20261007 / memory-remember). */
    public static Memory proposed(
        Long ownerUserId,
        String title,
        String content,
        MemoryPlacement placement,
        Long proposedByExecutionId,
        String proposalDedupKey,
        Instant now) {
        Memory memory = new Memory(
            MemoryScope.USER,
            ownerUserId,
            null,
            title,
            content,
            placement,
            MemoryStatus.PROPOSED,
            proposedByExecutionId,
            now);
        memory.proposalDedupKey = proposalDedupKey;
        return memory;
    }


    /**
     * 사용자가 대화에서 직접 말해 바로 저장한 개인 항목이다(ADR-20261007 / memory-remember). 그 말을 승인으로 보아 곧 ACCEPTED 이고 승인한 사람은
     * 주인이다.
     */
    public static Memory remembered(
        Long ownerUserId,
        String title,
        String content,
        MemoryPlacement placement,
        Long proposedByExecutionId,
        String proposalDedupKey,
        Instant now) {
        Memory memory = accepted(MemoryScope.USER, ownerUserId, null, title, content, placement, ownerUserId, now);
        memory.proposedByExecutionId = proposedByExecutionId;
        memory.proposalDedupKey = proposalDedupKey;
        return memory;
    }


    /** 자동 보관 허락 표시를 정한다. {@code memory_remember} 가 새 항목을 만들 때 모델의 인자로 부른다. */
    public void allowArchive(boolean value) {
        archivable = value;
    }


    /** 사람이 화면에서 정한 자동 보관 허락 표시를 적는다. null 이면 지금 값을 그대로 둔다. */
    public Memory withArchivable(Boolean value) {
        if (value != null) {
            archivable = value;
        }
        return this;
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
     * 본문과 꺼내는 방식을 고치고 revision 을 하나 올린다. 풀지 못한 줄은 부르는 쪽이 먼저 거절한다.
     *
     * <p>고치기 전의 값은 부르는 쪽이 {@link MemoryRevision#of} 로 먼저 남긴다.
     *
     * @param sealed 주인 데이터 key 로 암호화한 제목과 새 본문. 암호화가 꺼진 설치면 null 이다
     */
    public void revise(
        String content,
        SealedMemoryText sealed,
        MemoryRetrieval retrieval,
        String updatedProposalDedupKey,
        Instant at) {
        String title = title();
        // 암호문을 받았으면 저장 필드에 바로 적어 평문이 저장 필드에 놓이는 순간이 없다. 암호화가 꺼진 설치만 평문을 적는다
        this.storedTitle = sealed == null ? title : sealed.title();
        this.storedContent = sealed == null ? content : sealed.content();
        this.dataKeyId = sealed == null ? null : sealed.dataKeyId();
        this.plain = new MemoryText(title, content);
        this.unreadable = false;
        place(retrieval);
        if (proposalDedupKey != null) {
            proposalDedupKey = updatedProposalDedupKey;
        }
        this.revision += 1;
        this.updatedAt = at;
    }


    /** 제목이다. 암호문이면 처음 부를 때 푼다. 풀지 못하면 {@link #UNREADABLE_TITLE} 이다. */
    public String title() {
        return opened().map(MemoryText::title).orElse(UNREADABLE_TITLE);
    }


    /** 본문이다. 암호문이면 처음 부를 때 푼다. 풀지 못하면 빈 글이다. */
    public String content() {
        return opened().map(MemoryText::content).orElse("");
    }


    /** 제목과 본문을 풀었는가. 거짓이면 실행에 싣지 않고 고치지 않는다. */
    public boolean readable() {
        return opened().isPresent();
    }


    /** 저장된 글이 데이터 key 의 암호문인가. */
    public boolean sealed() {
        return dataKeyId != null;
    }


    /** 저장된 글과 key, 주인이다. 암호화와 풀기가 같은 AAD 를 만든다. */
    public StoredMemoryText stored() {
        return new StoredMemoryText(
            id, scope, scope == MemoryScope.USER ? ownerUserId : groupId, storedTitle, storedContent, dataKeyId);
    }


    /** 읽어 올 때 복호화 도구를 붙인다. */
    public void attachOpener(MemoryTextOpener opener) {
        this.opener = opener;
    }


    /**
     * 처음 넣기 전에 저장 필드를 빈 글로 바꾼다. 푼 글은 그대로 둔다. 줄 번호가 생긴 뒤 같은 트랜잭션에서 {@link #seal} 로 암호문을
     * 적어 데이터베이스에 평문이 쓰이지 않게 한다(ADR-20261103 / memory-encryption).
     */
    public void detachForSealing() {
        opened();
        this.storedTitle = "";
        this.storedContent = "";
    }


    /** 주인 데이터 key 의 암호문을 적는다. 판 번호와 갱신 시각은 건드리지 않는다. */
    public void seal(SealedMemoryText sealed) {
        opened();
        this.storedTitle = sealed.title();
        this.storedContent = sealed.content();
        this.dataKeyId = sealed.dataKeyId();
    }


    /**
     * 제안 중복 키를 주인 key 의 HMAC 으로 바꿔 적는다. 비어 있던 줄은 그대로 둔다(ADR-20261103 / memory-sensitivity-removal).
     *
     * @param dedupKey 새 키. 같은 키를 이미 다른 줄이 가졌으면 null 로 비운다
     */
    public void rekeyProposalDedup(String dedupKey) {
        if (proposalDedupKey != null) {
            proposalDedupKey = dedupKey;
        }
    }


    private Optional<MemoryText> opened() {
        if (plain == null && !unreadable) {
            Optional<MemoryText> read = sealed()
                ? (opener == null ? Optional.empty() : opener.open(stored()))
                : Optional.of(new MemoryText(storedTitle, storedContent));
            plain = read.orElse(null);
            unreadable = read.isEmpty();
        }
        return Optional.ofNullable(plain);
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
