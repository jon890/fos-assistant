package com.bifos.assistant.task.domain;

import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
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
 * 발화 한 번이다(ADR-072).
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/task.md} 의 「task_run」 이 갖는다. 같은 trigger 의 같은 예정 시각은 한 줄뿐이다.
 *
 * <p>시각 칸은 {@code DATETIME(6)} 이라 마이크로초까지만 둔다.
 */
@Entity
@Table(
        name = "task_run",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_task_run_public_id", columnNames = "public_id"),
            @UniqueConstraint(
                    name = "uk_task_run_trigger_scheduled",
                    columnNames = {"trigger_id", "scheduled_for"})
        })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화면과 API 가 쓰는 발화 번호다. 넣을 때 Hibernate 가 v7 을 채운다. */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "trigger_id", nullable = false, updatable = false)
    private Long triggerId;

    /** 그 발화 때의 작업 주인이다. 하루 발화 수를 조인 없이 센다. */
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "scheduled_for", nullable = false, updatable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskRunStatus status;

    /** {@code SKIPPED} 와 {@code FAILED} 의 까닭이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private TaskRunReason reason;

    /** 결과를 남긴 대화다. 시작 전에 끝난 줄은 비어 있을 수 있다. */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 이 발화의 루트 실행 번호다. */
    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 열기를 기다리는 발화다. */
    public static TaskRun queued(Long taskId, Long triggerId, Long ownerUserId, Instant scheduledFor, Instant now) {
        return created(taskId, triggerId, ownerUserId, scheduledFor, TaskRunStatus.QUEUED, null, now);
    }

    /** 열지 않고 건너뛴 발화다. 만든 때가 끝난 때다. */
    public static TaskRun skipped(
            Long taskId, Long triggerId, Long ownerUserId, Instant scheduledFor, TaskRunReason reason, Instant now) {
        TaskRun run = created(
                taskId,
                triggerId,
                ownerUserId,
                scheduledFor,
                TaskRunStatus.SKIPPED,
                Objects.requireNonNull(reason, "reason"),
                now);
        run.finishedAt = run.createdAt;
        return run;
    }

    private static TaskRun created(
            Long taskId,
            Long triggerId,
            Long ownerUserId,
            Instant scheduledFor,
            TaskRunStatus status,
            TaskRunReason reason,
            Instant now) {
        TaskRun run = new TaskRun();
        run.taskId = Objects.requireNonNull(taskId, "taskId");
        run.triggerId = Objects.requireNonNull(triggerId, "triggerId");
        run.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        run.scheduledFor = Objects.requireNonNull(scheduledFor, "scheduledFor").truncatedTo(ChronoUnit.MICROS);
        run.status = status;
        run.reason = reason;
        run.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        return run;
    }
}
