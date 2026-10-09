package com.bifos.assistant.task.domain;

import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TriggerType;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 예약 작업의 시각이다(ADR-077). 지금은 작업 하나에 하나다.
 *
 * <p>칸의 뜻은 {@code backend/docs/data-schema.md} 의 「task_trigger」 가 갖는다. 시각을 고치면 이 줄을 고친다. 발화 기록의 유일
 * 제약이 이 줄의 번호를 쓰므로 줄을 새로 만들지 않는다.
 *
 * <p>시각 칸은 {@code DATETIME(6)} 이라 마이크로초까지만 둔다.
 */
@Entity
@Table(
        name = "task_trigger",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_trigger_task", columnNames = "task_id"))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskTrigger {

    public static final int CRON_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 반복 시각이다. cron 과 다음 시각의 검사는 부르는 쪽이 끝낸다. */
    public static TaskTrigger cron(
            Long taskId, String cronExpr, ZoneId zone, MissedPolicy missed, Instant nextFireAt, Instant now) {
        TaskTrigger trigger = created(taskId, missed, now);
        trigger.reschedule(TriggerType.CRON, Objects.requireNonNull(cronExpr, "cronExpr"), null, zone, nextFireAt, now);
        return trigger;
    }

    /** 한 번 도는 시각이다. 다음 예정 시각은 그 시각이다. */
    public static TaskTrigger once(Long taskId, Instant fireAt, ZoneId zone, MissedPolicy missed, Instant now) {
        TaskTrigger trigger = created(taskId, missed, now);
        Objects.requireNonNull(fireAt, "fireAt");
        trigger.reschedule(TriggerType.ONCE, null, fireAt, zone, fireAt, now);
        return trigger;
    }

    /**
     * 시각을 바꾼다. 종류에 맞지 않는 칸은 비운다.
     *
     * @param cronExpr {@code CRON} 일 때만 쓴다
     * @param fireAt {@code ONCE} 일 때만 쓴다
     * @param nextFireAt 새 시각으로 계산한 다음 예정 시각. 없으면 null
     */
    public void reschedule(
            TriggerType type, String cronExpr, Instant fireAt, ZoneId zone, Instant nextFireAt, Instant now) {
        this.type = Objects.requireNonNull(type, "type");
        this.cronExpr = type == TriggerType.CRON ? cronExpr : null;
        this.fireAt = type == TriggerType.ONCE ? micros(fireAt) : null;
        this.timeZone = Objects.requireNonNull(zone, "zone").getId();
        this.nextFireAt = micros(nextFireAt);
        this.updatedAt = micros(now);
    }

    /** 놓친 발화를 어떻게 다룰지 바꾼다. 시각은 그대로다. */
    public void changeMissedPolicy(MissedPolicy missed, Instant now) {
        this.missedPolicy = Objects.requireNonNull(missed, "missed");
        this.updatedAt = micros(now);
    }

    /** 다음 예정 시각만 다시 정한다. 다시 켤 때 쓴다. 없으면 null 이다. */
    public void moveNext(Instant nextFireAt, Instant now) {
        this.nextFireAt = micros(nextFireAt);
        this.updatedAt = micros(now);
    }

    /**
     * 예정 시각 하나를 처리했다고 적고 다음 시각으로 옮긴다.
     *
     * @param lastFired 처리한 예정 시각
     * @param next 그 뒤의 다음 예정 시각. {@code ONCE} 와 다음 시각이 없는 cron 은 null
     */
    public void advance(Instant lastFired, Instant next, Instant now) {
        this.lastFiredAt = micros(Objects.requireNonNull(lastFired, "lastFired"));
        this.nextFireAt = micros(next);
        this.updatedAt = micros(now);
    }

    /** 저장된 시간대 이름을 읽은 값이다. */
    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }

    private static TaskTrigger created(Long taskId, MissedPolicy missed, Instant now) {
        TaskTrigger trigger = new TaskTrigger();
        trigger.taskId = Objects.requireNonNull(taskId, "taskId");
        trigger.missedPolicy = Objects.requireNonNull(missed, "missed");
        trigger.createdAt = micros(Objects.requireNonNull(now, "now"));
        return trigger;
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
