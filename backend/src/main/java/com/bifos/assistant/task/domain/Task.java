package com.bifos.assistant.task.domain;

import com.bifos.assistant.shared.domain.AuditedEntity;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.ZoneId;
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
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「task」 가 갖는다. 일정도 이 줄에 둔다. 지우면 줄을
 * 남기고 {@code ARCHIVED} 로 둔다. 발화 기록과 대화가 이 줄을 가리킨다.
 *
 * <p>시각 칸은 {@code DATETIME(6)} 이라 마이크로초까지만 둔다. 메모리의 값과 DB 에서 다시 읽은 값이 같아야 한다.
 */
@Entity
@AttributeOverride(name = "updatedAt", column = @Column(name = "updated_at", nullable = false))
@Table(name = "task", uniqueConstraints = @UniqueConstraint(name = "uk_task_public_id", columnNames = "public_id"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task extends AuditedEntity {

    /** 평문 제목의 글자 수 상한이다. 칸은 암호문이 들어가도록 이보다 넓다. */
    public static final int TITLE_MAX = 100;

    /** 칸의 길이다. 암호문은 평문 UTF-8 바이트의 약 4/3 에 44자쯤이 붙는다(ADR-20261008 / data-encryption). */
    private static final int TITLE_COLUMN = 512;

    /** 풀지 못한 제목이나 지시문 대신 내는 글이다. 데이터 key 가 없거나 암호문이 다른 줄에서 옮겨 왔을 때다. */
    public static final String UNREADABLE_CONTENT = "읽을 수 없는 글입니다.";

    /** 지시 글의 상한이다. 대화 메시지 상한과 같다. */
    public static final int INSTRUCTION_MAX = 8000;

    /** cron식 저장 길이의 상한이다. */
    public static final int CRON_MAX = 100;

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

    /** 칸에 저장된 제목 그대로다. {@link #titleKeyId} 가 있으면 암호문이다. 제목을 내는 곳은 {@link #title()} 을 쓴다. */
    @Column(name = "title", nullable = false, length = TITLE_COLUMN)
    private String storedTitle;

    /** 제목을 암호화한 데이터 key({@code user_data_key.id}). 비어 있으면 제목은 평문이다. */
    @Column(name = "title_key_id")
    private Long titleKeyId;

    /** 칸에 저장된 지시문 그대로다. {@link #instructionKeyId} 가 있으면 암호문이다. 점검 작업은 비어 있다. */
    @Column(name = "instruction", columnDefinition = "TEXT")
    private String storedInstruction;

    /** 지시문을 암호화한 데이터 key. 비어 있으면 지시문은 평문이거나 없다. */
    @Column(name = "instruction_key_id")
    private Long instructionKeyId;

    /** 푼 제목이다. 처음 꺼낼 때 채운다. 저장하지 않는다. */
    @Transient
    @Getter(AccessLevel.NONE)
    private volatile String plainTitle;

    /** 푼 지시문이다. 처음 꺼낼 때 채운다. 저장하지 않는다. */
    @Transient
    @Getter(AccessLevel.NONE)
    private volatile String plainInstruction;

    /** 읽어 올 때 붙인 복호화 도구다. 새로 만든 작업은 비어 있다. */
    @Transient
    @Getter(AccessLevel.NONE)
    private TaskContentOpener opener;

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

    /** 지운 시각. {@code state} 가 {@code ARCHIVED} 일 때만 찬다. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType type;

    /** {@code CRON} 일 때 5필드 cron. {@code ONCE} 는 비운다. */
    @Column(name = "cron_expr", length = CRON_MAX)
    private String cronExpr;

    /** {@code ONCE} 일 때 그 시각(UTC). {@code CRON} 은 비운다. */
    @Column(name = "fire_at")
    private Instant fireAt;

    /** IANA 시간대 이름이다. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "missed_policy", nullable = false, length = 20)
    private MissedPolicy missedPolicy;

    /** 다음 예정 시각(UTC). 한 번 발화한 {@code ONCE} 와 다음 시각이 없는 작업은 비어 있다. */
    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    /** 마지막으로 처리한 예정 시각이다. */
    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    /** {@code CHECK} 작업의 매일 루프 동의다. 일정의 켜짐({@code state})과 다른 동의라 따로 둔다. */
    @Column(name = "loop_enabled", nullable = false)
    private boolean loopEnabled;

    /** {@code CHECK} 작업의 매일 루프 쉬기 끝 시각이다. 이 시각 전의 깨우기는 루프로 잇지 않는다. 비어 있으면 쉬지 않는다. */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;


    /**
     * 시각을 바꾼다. 종류에 맞지 않는 칸은 비운다.
     *
     * @param cronExpr {@code CRON} 일 때만 쓴다
     * @param fireAt {@code ONCE} 일 때만 쓴다
     * @param nextFireAt 새 시각으로 계산한 다음 예정 시각. 없으면 null
     */
    public void reschedule(
        TriggerType type, String cronExpr, Instant fireAt, ZoneId zone, Instant nextFireAt) {
        this.type = Objects.requireNonNull(type, "type");
        this.cronExpr = type == TriggerType.CRON ? cronExpr : null;
        this.fireAt = type == TriggerType.ONCE ? micros(fireAt) : null;
        this.timeZone = Objects.requireNonNull(zone, "zone").getId();
        this.nextFireAt = micros(nextFireAt);
    }


    /** 놓친 발화를 어떻게 다룰지 바꾼다. 시각은 그대로다. */
    public void changeMissedPolicy(MissedPolicy missed) {
        this.missedPolicy = Objects.requireNonNull(missed, "missed");
    }


    /** 다음 예정 시각만 다시 정한다. 다시 켤 때 쓴다. 없으면 null 이다. */
    public void moveNext(Instant nextFireAt) {
        this.nextFireAt = micros(nextFireAt);
    }


    /**
     * 예정 시각 하나를 처리했다고 적고 다음 시각으로 옮긴다.
     *
     * @param lastFired 처리한 예정 시각
     * @param next 그 뒤의 다음 예정 시각. {@code ONCE} 와 다음 시각이 없는 cron 은 null
     */
    public void advance(Instant lastFired, Instant next) {
        this.lastFiredAt = micros(Objects.requireNonNull(lastFired, "lastFired"));
        this.nextFireAt = micros(next);
    }


    /**
     * 제목을 낸다. 암호문이면 처음 부를 때 풀어 둔다. 풀지 못하면 {@link #UNREADABLE_CONTENT} 다.
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
        opened = opener == null ? UNREADABLE_CONTENT : opener.openTitle(this);
        plainTitle = opened;
        return opened;
    }


    /** 지시문을 낸다. 규칙은 {@link #title()} 과 같다. 점검 작업은 null 이다. */
    public String instruction() {
        String opened = plainInstruction;
        if (opened != null) {
            return opened;
        }
        if (instructionKeyId == null) {
            return storedInstruction;
        }
        opened = opener == null ? UNREADABLE_CONTENT : opener.openInstruction(this);
        plainInstruction = opened;
        return opened;
    }


    public void attachOpener(TaskContentOpener opener) {
        this.opener = opener;
    }


    /**
     * 처음 저장하기 전에 평문 제목과 지시문을 칸에서 빼 둔다. 제목 칸에는 빈 글이, 지시문 칸에는 null 이 들어간다.
     *
     * <p>줄 번호가 생긴 뒤에야 AAD 를 만들 수 있어, 비워 넣은 다음 같은 트랜잭션에서 {@link #sealContent} 로 암호문을 적는다.
     * 데이터베이스에 평문이 한 번도 쓰이지 않는다. 푼 글은 이 객체가 계속 들고 있다.
     */
    public void detachPlainForSealing() {
        this.plainTitle = storedTitle;
        this.plainInstruction = storedInstruction;
        this.storedTitle = "";
        this.storedInstruction = null;
    }


    /** 암호문과 그 데이터 key 를 적는다. 지시문이 없으면({@code null}) 그 칸은 비운 채 둔다. */
    public void sealContent(String sealedTitle, Long titleKey, String sealedInstruction, Long instructionKey) {
        this.plainTitle = title();
        this.plainInstruction = instruction();
        this.storedTitle = sealedTitle;
        this.titleKeyId = titleKey;
        this.storedInstruction = sealedInstruction;
        this.instructionKeyId = instructionKey;
    }


    /** 암호문을 풀어 평문과 빈 key 칸으로 되돌린다. 옛 버전으로 내리기 전의 풀기 배치만 쓴다. 지시문이 없으면({@code null}) 그 칸은 비운다. */
    public void unsealContent(String title, String instruction) {
        this.plainTitle = title;
        this.plainInstruction = instruction;
        this.storedTitle = title;
        this.titleKeyId = null;
        this.storedInstruction = instruction;
        this.instructionKeyId = null;
    }


    /** 저장된 시간대 이름을 읽은 값이다. */
    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }


    /** {@code ACTIVE} 인 새 작업이다. 값의 검사는 부르는 쪽이 끝낸다. */
    public static Task create(
        Long ownerUserId,
        Long agentId,
        String title,
        String instruction,
        ConversationMode mode,
        NotifyPolicy notify) {
        Task task = new Task();
        task.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        task.kind = TaskKind.TURN;
        task.state = TaskState.ACTIVE;
        task.apply(agentId, title, instruction, mode, notify);
        return task;
    }


    /** 에이전트 하나의 매일 깨우기 설정이다. */
    public static Task check(Long ownerUserId, Long agentId, String title) {
        Task task = new Task();
        task.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        task.agentId = Objects.requireNonNull(agentId, "agentId");
        task.storedTitle = Objects.requireNonNull(title, "title");
        task.kind = TaskKind.CHECK;
        task.storedInstruction = null;
        task.state = TaskState.ACTIVE;
        task.conversationMode = ConversationMode.SINGLE;
        task.notifyPolicy = NotifyPolicy.NEVER;
        return task;
    }


    /** 매일 깨우기의 켜짐 상태를 바꾼다. */
    public void changeCheckEnabled(boolean enabled) {
        if (kind != TaskKind.CHECK) {
            throw new IllegalStateException("only CHECK tasks can change the proactive schedule");
        }
        state = enabled ? TaskState.ACTIVE : TaskState.PAUSED;
    }


    /** 매일 루프의 동의와 쉬기 끝 시각을 정한다. 일정의 켜짐은 바꾸지 않는다. {@code CHECK} 작업만 쓴다. */
    public void changeLoop(boolean enabled, Instant snoozeUntil) {
        if (kind != TaskKind.CHECK) {
            throw new IllegalStateException("only CHECK tasks can hold the proactive loop setting");
        }
        this.loopEnabled = enabled;
        this.snoozedUntil = micros(snoozeUntil);
    }


    /** 에이전트, 이름, 지시, 대화 방식, 알림을 고친다. 일정은 reschedule로 고친다. */
    public void edit(
        Long agentId, String title, String instruction, ConversationMode mode, NotifyPolicy notify) {
        apply(agentId, title, instruction, mode, notify);
    }


    /** 모델 단계를 고른다. null 이면 단계를 지운다. {@code TURN} 작업만 고를 수 있다. */
    public void chooseModelTier(ModelTier tier) {
        if (kind != TaskKind.TURN) {
            throw new IllegalStateException("only TURN tasks can choose a model tier");
        }
        modelTier = tier;
    }


    /**
     * 멈춘다. {@code ACTIVE} 만 바꾼다.
     *
     * @return 바꿨으면 true
     */
    public boolean pause() {
        if (state != TaskState.ACTIVE) {
            return false;
        }
        state = TaskState.PAUSED;
        return true;
    }


    /**
     * 다시 켠다. {@code PAUSED} 만 바꾼다.
     *
     * @return 바꿨으면 true
     */
    public boolean resume() {
        if (state != TaskState.PAUSED) {
            return false;
        }
        state = TaskState.ACTIVE;
        return true;
    }


    /** 지운다. 이미 지웠으면 처음 지운 시각을 그대로 둔다. */
    public void archive(Instant now) {
        if (state == TaskState.ARCHIVED) {
            return;
        }
        state = TaskState.ARCHIVED;
        archivedAt = micros(Objects.requireNonNull(now, "now"));
    }


    public boolean archived() {
        return state == TaskState.ARCHIVED;
    }


    private void apply(
        Long agentId, String title, String instruction, ConversationMode mode, NotifyPolicy notify) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.storedTitle = Objects.requireNonNull(title, "title");
        this.titleKeyId = null;
        this.plainTitle = null;
        this.storedInstruction = Objects.requireNonNull(instruction, "instruction");
        this.instructionKeyId = null;
        this.plainInstruction = null;
        this.conversationMode = Objects.requireNonNull(mode, "mode");
        this.notifyPolicy = Objects.requireNonNull(notify, "notify");
    }


    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
