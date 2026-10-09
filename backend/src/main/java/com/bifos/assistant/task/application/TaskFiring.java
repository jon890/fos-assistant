package com.bifos.assistant.task.application;

import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예정 시각이 된 작업의 발화를 {@code task_run} 줄로 한 번만 만든다(ADR-077).
 *
 * <p>규칙은 {@code docs/features/schedule.md} 의 「발화」 가 갖는다. 발화는 짧은 트랜잭션이고 turn 은 {@link TaskRunStarter} 가
 * 트랜잭션 밖에서 연다. trigger 마다 트랜잭션 하나에서 줄을 잠그고 다시 읽는다. 한 trigger 의 실패는 그 trigger 만
 * 되돌리고 다음 tick 에 다시 시도된다. 같은 {@code (trigger_id, scheduled_for)} 줄은 먼저 보고 거르며, 유일 제약이 마지막
 * 방어선이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskFiring {

    /** 하루 발화 수를 세는 기간이다. */
    private static final Duration DAY = Duration.ofHours(24);

    private final TaskTriggerRepository triggers;
    private final TaskRepository tasks;
    private final TaskRunRepository runs;
    private final TaskNotices notices;
    private final TaskProperties properties;
    private final TransactionTemplate transactions;

    /**
     * {@code now} 에 다음 예정 시각이 지났고 작업이 {@code ACTIVE} 인 trigger 를 예정 시각 순으로 처리한다.
     *
     * @return 새로 만든 발화 줄 수. 건너뛴 줄도 센다
     */
    public int fireDue(Instant now) {
        List<Long> due = triggers.findDueIds(now, TaskState.ACTIVE);
        int created = 0;
        for (Long triggerId : due) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> fireOne(triggerId, now)))) {
                    created++;
                }
            } catch (RuntimeException ex) {
                log.warn("예약 작업을 발화하지 못했다 triggerId={}", triggerId, ex);
            }
        }
        return created;
    }

    /**
     * trigger 하나를 잠그고 다시 읽어 발화한다. 부르는 쪽의 트랜잭션 안에서 돈다.
     *
     * @return 새 줄을 만들었으면 true
     */
    private boolean fireOne(Long triggerId, Instant now) {
        TaskTrigger trigger = triggers.findByIdForUpdate(triggerId).orElse(null);
        if (trigger == null
                || trigger.nextFireAt() == null
                || trigger.nextFireAt().isAfter(now)) {
            return false;
        }
        Task task = tasks.findById(trigger.taskId()).orElse(null);
        if (task == null || task.state() != TaskState.ACTIVE) {
            return false;
        }
        ZoneId zone = trigger.zone();
        CronExpression cron = trigger.type() == TriggerType.CRON ? TaskSchedule.parseCron(trigger.cronExpr()) : null;
        Instant scheduled = trigger.nextFireAt();
        boolean late = scheduled.plus(properties.missedGrace()).isBefore(now);
        TaskRunStatus outcome = TaskRunStatus.QUEUED;
        TaskRunReason reason = null;
        if (late && trigger.missedPolicy() == MissedPolicy.SKIP) {
            outcome = TaskRunStatus.SKIPPED;
            reason = TaskRunReason.MISSED;
        } else if (late && cron != null) {
            Instant latest = TaskSchedule.latestAtOrBefore(cron, zone, scheduled, now);
            scheduled = latest == null ? scheduled : latest;
        }
        Instant next = cron == null ? null : TaskSchedule.nextAfter(cron, zone, now);
        trigger.advance(scheduled, next, now);
        if (runs.existsByTriggerIdAndScheduledFor(trigger.id(), scheduled)) {
            return false;
        }
        if (outcome == TaskRunStatus.QUEUED && reachedDailyLimit(task, now)) {
            outcome = TaskRunStatus.SKIPPED;
            reason = TaskRunReason.DAILY_LIMIT;
        }
        TaskRun run = outcome == TaskRunStatus.QUEUED
                ? TaskRun.queued(task.id(), trigger.id(), task.ownerUserId(), scheduled, now)
                : TaskRun.skipped(task.id(), trigger.id(), task.ownerUserId(), scheduled, reason, now);
        runs.saveAndFlush(run);
        notices.announce(task, run);
        return true;
    }

    /** 그 주인이 24시간 안에 건너뛰지 않은 발화를 상한만큼 만들었는가. */
    private boolean reachedDailyLimit(Task task, Instant now) {
        long recent = runs.countByOwnerUserIdAndCreatedAtAfterAndStatusNot(
                task.ownerUserId(), now.minus(DAY), TaskRunStatus.SKIPPED);
        return recent >= properties.maxRunsPerDay();
    }
}
