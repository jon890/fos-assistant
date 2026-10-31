package com.bifos.assistant.task.application;

import com.bifos.assistant.proactive.loop.application.dto.LoopScheduleState;
import com.bifos.assistant.proactive.loop.application.port.LoopScheduleSettings;
import com.bifos.assistant.task.config.TaskProperties;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 루프의 켜짐과 쉬기를 사용자와 에이전트마다 하나인 CHECK 작업에서 읽고 쓴다(ADR-20261101 / proactive-suggestion).
 *
 * <p>켜짐은 작업이 {@code ACTIVE} 인 것이다. 옛 루프 동의만으로 꺼진 작업을 켜지 않으며, 작업이 없는 사용자가 처음 켜면 기본 시각의
 * 작업을 만든다. 발화 직전에 권한과 준비 상태를 다시 검사하므로 여기서는 검사하지 않는다.
 */
@Component
@RequiredArgsConstructor
class TaskLoopSettings implements LoopScheduleSettings {

    private static final String DEFAULT_CRON = "0 9 * * *";
    private static final String CHECK_TITLE = "매일 먼저 살펴보기";

    private final TaskRepository tasks;
    private final TaskProperties taskProperties;


    @Override
    @Transactional(readOnly = true)
    public Optional<LoopScheduleState> find(Long userId, Long agentId) {
        return tasks.findByOwnerUserIdAndAgentIdAndKind(userId, agentId, TaskKind.CHECK)
            .filter(task -> !task.archived())
            .map(TaskLoopSettings::stateOf);
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LoopScheduleState> findLockingUser(Long userId, Long agentId) {
        return tasks.findByOwnerUserIdAndKindOrderByIdAscForUpdate(userId, TaskKind.CHECK).stream()
            .filter(task -> task.agentId().equals(agentId) && !task.archived())
            .findFirst()
            .map(TaskLoopSettings::stateOf);
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoopScheduleState save(Long userId, Long agentId, boolean enabled, Instant snoozedUntil, Instant now) {
        Task task = tasks.findByOwnerUserIdAndAgentIdAndKindForUpdate(userId, agentId, TaskKind.CHECK)
            .orElse(null);
        if (task == null) {
            if (!enabled && snoozedUntil == null) {
                return new LoopScheduleState(false, null);
            }
            task = Task.check(userId, agentId, CHECK_TITLE);
            ZoneId zone = taskProperties.defaultZone();
            Instant next = TaskSchedule.nextAfter(TaskSchedule.parseCron(DEFAULT_CRON), zone, now);
            task.reschedule(TriggerType.CRON, DEFAULT_CRON, null, zone, next);
            task.changeMissedPolicy(MissedPolicy.SKIP);
        }
        if (task.archived()) {
            return new LoopScheduleState(false, null);
        }
        if (enabled && task.state() == TaskState.PAUSED) {
            task.resume();
            task.moveNext(TaskSchedule.nextAfter(TaskSchedule.parseCron(task.cronExpr()), task.zone(), now));
        } else if (!enabled) {
            task.changeCheckEnabled(false);
        }
        task.snoozeUntil(snoozedUntil);
        return stateOf(tasks.saveAndFlush(task));
    }


    private static LoopScheduleState stateOf(Task task) {
        return new LoopScheduleState(task.state() == TaskState.ACTIVE, task.snoozedUntil());
    }
}
