package com.bifos.assistant.task.application;

import com.bifos.assistant.proactive.loop.application.dto.LoopScheduleState;
import com.bifos.assistant.proactive.loop.application.port.LoopScheduleSettings;
import com.bifos.assistant.task.config.TaskProperties;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
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
 * <p>루프 동의({@code loop_enabled})는 일정의 켜짐({@code state})과 다른 동의다. 이 클래스는 동의와 쉬기만 바꾸고 일정의 켜짐은 바꾸지
 * 않는다. 작업이 없는 사용자가 처음 저장하면 일정이 꺼진 작업을 만들어 동의를 담는다.
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
            // 동의만 담는 줄이다. 일정은 사용자가 따로 켠다.
            task.changeCheckEnabled(false);
        }
        if (task.archived()) {
            return new LoopScheduleState(false, null);
        }
        task.changeLoop(enabled, snoozedUntil);
        return stateOf(tasks.saveAndFlush(task));
    }


    private static LoopScheduleState stateOf(Task task) {
        return new LoopScheduleState(task.loopEnabled(), task.snoozedUntil());
    }
}
