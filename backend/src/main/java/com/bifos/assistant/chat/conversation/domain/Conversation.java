package com.bifos.assistant.chat.conversation.domain;

import com.bifos.assistant.shared.domain.CreatedAtEntity;

import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.ExecutionConversation;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class Conversation extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    /**
     * 주소와 API 에 내보내는 식별자다. 번호는 Control Plane 안에서만 쓴다.
     *
     * <p>넣을 때 Hibernate 가 v7 을 채운다. {@code BINARY} 로 못 박아 테스트의 H2 와 운영의 MySQL 이 같은
     * 바이트 16개로 저장한다.
     */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true, columnDefinition = "BINARY(16)")
    @Getter
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    @Getter
    private Long userId;

    @Column(name = "agent_id")
    @Getter
    private Long agentId;

    /**
     * 다음 turn 에 보낼 Hermes session 이다.
     *
     * <p>새 대화는 첫 turn 을 보내기 전에 Control Plane 이 정한다. 압축 교체로 Hermes 가 다른 session 을
     * 돌려주면 그 값으로 바뀐다.
     */
    @Column(name = "hermes_session_id", length = 128)
    @Getter
    private String hermesSessionId;

    /**
     * 이 대화의 첫 Hermes session. 한 번 정하면 바뀌지 않는다.
     *
     * <p>MCP {@code agent_*} 호출이 들고 오는 서명한 루트 session 이 이 값이다(ADR-031). 이 칸이 생기기
     * 전에 Hermes 가 session 을 정한 대화는 비어 있다.
     */
    @Column(name = "hermes_root_session_id", length = 128)
    @Getter
    private String hermesRootSessionId;

    /** 풀지 못한 제목 대신 내는 글이다. 데이터 key 가 없거나 암호문이 다른 줄에서 옮겨 왔을 때다. */
    public static final String UNREADABLE_TITLE = "읽을 수 없는 제목입니다.";

    /**
     * 칸에 저장된 글 그대로다. {@link #titleKeyId} 가 있으면 암호문이다(ADR-20261008 / data-encryption). 빈 제목은 평문 빈 글이다.
     *
     * <p>제목을 내는 곳은 {@link #title()} 을 쓴다. 칸 길이는 암호문이 들어가도록 평문 상한(200자)보다 넓다.
     */
    @Column(name = "title", nullable = false, length = 1024)
    @Getter
    private String storedTitle;

    /** 제목을 암호화한 데이터 key({@code user_data_key.id}). 비어 있으면 {@link #storedTitle} 은 평문이다. */
    @Column(name = "title_key_id")
    @Getter
    private Long titleKeyId;

    /** 푼 제목이다. 처음 꺼낼 때 채운다. 저장하지 않는다. */
    @Transient
    @Getter(AccessLevel.NONE)
    private volatile String plainTitle;

    /** 읽어 올 때 붙인 복호화 도구다. 새로 만든 대화는 비어 있다. */
    @Transient
    @Getter(AccessLevel.NONE)
    private ConversationTitleOpener opener;

    /**
     * 보통 대화인지 먼저 살펴보기의 점검 대화인지다. 만들 때 정하고 바뀌지 않는다.
     *
     * <p>사용자와 에이전트마다 지우지 않은 점검 대화 가운데 {@code id} 가 가장 큰 것을 쓴다(ADR-080).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 16)
    @Getter
    private ConversationPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_selection_mode", length = 16)
    @Getter
    private ModelSelectionMode modelSelectionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_tier", length = 16)
    @Getter
    private ModelTier modelTier;

    /**
     * 사용자의 질문 없이 Control Plane 이 연 turn 의 수다. 사용자가 질문을 보내면 0 으로 돌아간다.
     *
     * <p>이 칸만 바꾸는 갱신은 {@code ConversationRepository} 의 update 질의로 한다.
     */
    @Column(name = "auto_turn_count", nullable = false)
    @Getter
    private int autoTurnCount;

    /** 이 대화를 만든 예약 작업의 번호다. 사용자가 연 대화는 비어 있다(ADR-078). */
    @Column(name = "task_id")
    @Getter
    private Long taskId;

    @Column(name = "updated_at", nullable = false)
    @Getter
    private Instant updatedAt;

    @Column(name = "deleted_at")
    @Getter
    private Instant deletedAt;

    /** 지운 대화의 본문을 실제로 지운 시각이다. 비어 있으면 아직 지우지 않았다. */
    @Column(name = "purged_at")
    @Getter
    private Instant purgedAt;

    /**
     * 목록에서만 뺀 시각이다. 비면 목록에 보인다. 예약 작업이 「보고할 것 없음」 으로 끝낸 {@code NEW_PER_RUN} 대화에 적는다.
     *
     * <p>조회와 보내기는 그대로 된다. 사용자가 질문을 보내면 비운다. 이 칸은 {@code ConversationRepository} 의 update 질의로만
     * 바꾼다.
     */
    @Column(name = "hidden_at")
    @Getter
    private Instant hiddenAt;

    /**
     * 대기 줄의 JSON 이다(ADR-048). 비면 대기 메시지가 없다.
     *
     * <p>엔티티로 읽지도 쓰지도 않는다. 먼저 읽어 둔 대화를 저장할 때 그 사이 바뀐 대기 줄을 옛 값으로 덮지 않게 한다.
     * {@code PendingMessageStore} 가 줄을 잠그고 전용 질의로만 읽고 바꾼다. 칸을 매핑해 두는 것은 테스트가 엔티티로 만드는
     * 스키마에도 이 칸이 있게 하려는 것이다.
     */
    @Column(name = "pending_messages", insertable = false, updatable = false, columnDefinition = "LONGTEXT")
    @Getter(AccessLevel.NONE)
    private String pendingMessages;


    private Conversation(Long userId, String title, Long agentId, ConversationPurpose purpose, Instant now) {
        this.userId = userId;
        this.storedTitle = title;
        this.agentId = agentId;
        this.purpose = purpose;
        this.updatedAt = now;
    }


    public static Conversation startedBy(Long userId, String title, Long agentId, Instant now) {
        return new Conversation(userId, title, agentId, ConversationPurpose.CHAT, now);
    }


    /** 먼저 살펴보기의 결과가 남는 점검 대화를 만든다. */
    public static Conversation startedForCheck(Long userId, String title, Long agentId, Instant now) {
        return new Conversation(userId, title, agentId, ConversationPurpose.CHECK, now);
    }


    /** 예약 작업이 발화해 연 대화다. 제목은 작업 이름이다. */
    public static Conversation startedForTask(Long userId, String title, Long agentId, Long taskId, Instant now) {
        Conversation conversation = new Conversation(userId, title, agentId, ConversationPurpose.CHAT, now);
        conversation.taskId = taskId;
        return conversation;
    }


    /** 실행 줄이 이 대화에서 읽는 값만 담아 낸다. 대화 번호다. */
    public ExecutionConversation executionConversation() {
        return new ExecutionConversation(id);
    }


    public static String normalizedTitle(String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "conversation title must have 1 to 200 characters");
        }
        return normalized;
    }


    /**
     * 제목을 낸다. 암호문이면 처음 부를 때 풀어 둔다. 풀지 못하면 {@link #UNREADABLE_TITLE} 이다.
     *
     * <p>제목을 내는 곳은 모두 이 메서드를 거친다. 저장된 글이 필요하면 {@code storedTitle()} 을 쓴다.
     */
    public String title() {
        String opened = plainTitle;
        if (opened != null) {
            return opened;
        }
        if (titleKeyId == null) {
            return storedTitle;
        }
        opened = opener == null ? UNREADABLE_TITLE : opener.open(this);
        plainTitle = opened;
        return opened;
    }


    public void attachOpener(ConversationTitleOpener opener) {
        this.opener = opener;
    }


    /**
     * 처음 저장하기 전에 평문 제목을 칸에서 빼 둔다. 칸에는 빈 글이 들어간다.
     *
     * <p>줄 번호가 생긴 뒤에야 AAD 를 만들 수 있어, 빈 글로 넣은 다음 같은 트랜잭션에서 {@link #sealTitle} 로 암호문을 적는다.
     * 데이터베이스에 평문 제목이 한 번도 쓰이지 않는다.
     *
     * @return 뺀 평문
     */
    public String detachPlainTitleForSealing() {
        String detached = storedTitle;
        this.plainTitle = detached;
        this.storedTitle = "";
        return detached;
    }


    /** 암호문과 그 데이터 key 를 적는다. 푼 제목은 그대로 둔다. */
    public void sealTitle(String sealedTitle, Long keyId) {
        this.plainTitle = title();
        this.storedTitle = sealedTitle;
        this.titleKeyId = keyId;
    }


    /** 암호문 제목을 풀어 평문과 빈 key 칸으로 되돌린다. 옛 버전으로 내리기 전의 풀기 배치만 쓴다. */
    public void unsealTitle(String plain) {
        this.plainTitle = plain;
        this.storedTitle = plain;
        this.titleKeyId = null;
    }


    /**
     * 질의로 채운 제목을 이 객체에 옮긴다. 저장소의 같은 칸에 적은 값과 같게 맞춘다. 평문이면 key 번호가 비어 있다.
     *
     * <p>이미 읽어 둔 대화를 저장할 때 질의가 채운 제목을 빈 글이나 평문으로 덮지 않게 한다.
     */
    public void adoptTitle(String plain, String stored, Long keyId) {
        this.plainTitle = plain;
        this.storedTitle = stored;
        this.titleKeyId = keyId;
    }


    /**
     * Control Plane 이 정한 새 session 을 보낼 session 과 루트 session 에 함께 적는다.
     *
     * <p>{@code updatedAt} 은 바꾸지 않는다. turn 을 시작할 때 부르므로, 바꾸면 실패한 turn 도 대화를
     * 목록 맨 위로 올린다.
     */
    public void assignNewSession(String sessionId) {
        this.hermesSessionId = sessionId;
        this.hermesRootSessionId = sessionId;
    }


    /** 저장소에 이미 적힌 두 session 을 이 객체에 옮긴다. 다른 turn 이 먼저 정했을 때 쓴다. */
    public void adoptSessions(String sessionId, String rootSessionId) {
        this.hermesSessionId = sessionId;
        this.hermesRootSessionId = rootSessionId;
    }


    public void rememberSession(String sessionId, Instant now) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.hermesSessionId = sessionId;
        }
        this.updatedAt = now;
    }
}
