package com.bifos.assistant.task.domain;

import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 예약 작업 하나다(ADR-076).
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「task」 가 갖는다. 시각은 {@link TaskTrigger} 가 따로 갖는다. 지우면 줄을
 * 남기고 {@code ARCHIVED} 로 둔다. 발화 기록과 대화가 이 줄을 가리킨다.
 *
 * <p>시각 칸은 {@code DATETIME(6)} 이라 마이크로초까지만 둔다. 메모리의 값과 DB 에서 다시 읽은 값이 같아야 한다.
 */
@Entity
@Table(name = "task", uniqueConstraints = @UniqueConstraint(name = "uk_task_public_id", columnNames = "public_id"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {

    /** {@code title} 칸의 길이다. */
    public static final int TITLE_MAX = 100;

    /** 지시 글의 상한이다. 대화 메시지 상한과 같다. */
    public static final int INSTRUCTION_MAX = 8000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화면과 API 가 쓰는 작업 번호다. 넣을 때 Hibernate 가 v7 을 채운다. */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    /** 누구의 권한으로 도는가. 바뀌지 않는다. */
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "title", nullable = false, length = TITLE_MAX)
    private String title;

    @Column(name = "instruction", columnDefinition = "TEXT")
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TaskKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private TaskState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_mode", nullable = false, length = 20)
    private ConversationMode conversationMode;

    /** {@code SINGLE} 일 때 결과를 쌓는 대화. 첫 발화가 만들기 전에는 비어 있다. */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 알림 설정이다. {@code Object.notify()} 와 이름이 겹치지 않게 칸과 다른 이름을 쓴다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify", nullable = false, length = 20)
    private NotifyPolicy notifyPolicy;

    /** 발화하는 대화를 고를 모델 단계. 비면 대화의 선택을 건드리지 않는다. {@code CHECK} 작업은 늘 비어 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "model_tier", length = 16)
    private ModelTier modelTier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 지운 시각. {@code state} 가 {@code ARCHIVED} 일 때만 찬다. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** {@code ACTIVE} 인 새 작업이다. 값의 검사는 부르는 쪽이 끝낸다. */
    public static Task create(
            Long ownerUserId,
            Long agentId,
            String title,
            String instruction,
            ConversationMode mode,
            NotifyPolicy notify,
            Instant now) {
        Task task = new Task();
        task.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        task.kind = TaskKind.TURN;
        task.state = TaskState.ACTIVE;
        task.createdAt = micros(now);
        task.apply(agentId, title, instruction, mode, notify, now);
        return task;
    }

    /** 에이전트 하나의 매일 깨우기 설정이다. */
    public static Task check(Long ownerUserId, Long agentId, String title, Instant now) {
        Task task = new Task();
        task.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        task.agentId = Objects.requireNonNull(agentId, "agentId");
        task.title = Objects.requireNonNull(title, "title");
        task.kind = TaskKind.CHECK;
        task.instruction = null;
        task.state = TaskState.ACTIVE;
        task.conversationMode = ConversationMode.SINGLE;
        task.notifyPolicy = NotifyPolicy.NEVER;
        task.createdAt = micros(now);
        task.updatedAt = task.createdAt;
        return task;
    }

    /** 매일 깨우기의 켜짐 상태를 바꾼다. */
    public void changeCheckEnabled(boolean enabled, Instant now) {
        if (kind != TaskKind.CHECK) {
            throw new IllegalStateException("only CHECK tasks can change the proactive schedule");
        }
        state = enabled ? TaskState.ACTIVE : TaskState.PAUSED;
        updatedAt = micros(now);
    }

    /** 에이전트, 이름, 지시, 대화 방식, 알림을 고친다. 시각은 {@link TaskTrigger} 가 고친다. */
    public void edit(
            Long agentId, String title, String instruction, ConversationMode mode, NotifyPolicy notify, Instant now) {
        apply(agentId, title, instruction, mode, notify, now);
    }

    /** 모델 단계를 고른다. null 이면 단계를 지운다. {@code TURN} 작업만 고를 수 있다. */
    public void chooseModelTier(ModelTier tier, Instant now) {
        if (kind != TaskKind.TURN) {
            throw new IllegalStateException("only TURN tasks can choose a model tier");
        }
        modelTier = tier;
        updatedAt = micros(now);
    }

    /**
     * 멈춘다. {@code ACTIVE} 만 바꾼다.
     *
     * @return 바꿨으면 true
     */
    public boolean pause(Instant now) {
        if (state != TaskState.ACTIVE) {
            return false;
        }
        state = TaskState.PAUSED;
        updatedAt = micros(now);
        return true;
    }

    /**
     * 다시 켠다. {@code PAUSED} 만 바꾼다.
     *
     * @return 바꿨으면 true
     */
    public boolean resume(Instant now) {
        if (state != TaskState.PAUSED) {
            return false;
        }
        state = TaskState.ACTIVE;
        updatedAt = micros(now);
        return true;
    }

    /** 지운다. 이미 지웠으면 처음 지운 시각을 그대로 둔다. */
    public void archive(Instant now) {
        if (state == TaskState.ARCHIVED) {
            return;
        }
        state = TaskState.ARCHIVED;
        archivedAt = micros(now);
        updatedAt = archivedAt;
    }

    public boolean archived() {
        return state == TaskState.ARCHIVED;
    }

    private void apply(
            Long agentId, String title, String instruction, ConversationMode mode, NotifyPolicy notify, Instant now) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.title = Objects.requireNonNull(title, "title");
        this.instruction = Objects.requireNonNull(instruction, "instruction");
        this.conversationMode = Objects.requireNonNull(mode, "mode");
        this.notifyPolicy = Objects.requireNonNull(notify, "notify");
        this.updatedAt = micros(now);
    }

    private static Instant micros(Instant instant) {
        return Objects.requireNonNull(instant, "now").truncatedTo(ChronoUnit.MICROS);
    }
}
