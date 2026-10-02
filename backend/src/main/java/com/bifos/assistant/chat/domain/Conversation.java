package com.bifos.assistant.chat.domain;

import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
public class Conversation {

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

    @Column(name = "title", nullable = false, length = 200)
    @Getter
    private String title;

    /** 이 대화에서 고른 provider. {@code model} 과 함께 채우거나 함께 비운다. */
    @Column(name = "model_provider", length = ModelChoice.PROVIDER_MAX_LENGTH)
    private String modelProvider;

    /** 이 대화에서 고른 모델. 비면 그 profile 의 기본 모델로 돈다. */
    @Column(name = "model", length = ModelChoice.MODEL_MAX_LENGTH)
    private String model;

    /** 이 대화에서 고른 reasoning effort. 비면 그 profile 의 기본값이다. */
    @Column(name = "reasoning_effort", length = 16)
    private String reasoningEffort;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Getter
    private Instant updatedAt;

    @Column(name = "deleted_at")
    @Getter
    private Instant deletedAt;

    private Conversation(Long userId, String title, Long agentId, Instant now) {
        this.userId = userId;
        this.title = title;
        this.agentId = agentId;
        this.createdAt = now;
        this.updatedAt = this.createdAt;
    }

    public static Conversation startedBy(Long userId, String title, Long agentId, Instant now) {
        return new Conversation(userId, title, agentId, now);
    }

    /**
     * 이 대화에서 고른 모델과 effort 다. 고르지 않았으면 셋 다 null 이다.
     *
     * <p>저장된 값은 다시 검증하지 않는다. 검증 규칙이 바뀌어도 이미 있는 대화를 열고 보낼 수 있어야 한다.
     */
    public ModelChoice modelChoice() {
        return ModelChoice.stored(modelProvider, model, reasoningEffort);
    }

    public static String normalizedTitle(String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "conversation title must have 1 to 200 characters");
        }
        return normalized;
    }

    /** 제목이 비어 있을 때만 채운다. 사진을 먼저 올리려고 만든 대화는 첫 메시지가 제목을 정한다. */
    public void titleIfBlank(String title) {
        if (this.title == null || this.title.isBlank()) {
            this.title = title;
        }
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
