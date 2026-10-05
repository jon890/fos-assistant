package com.bifos.assistant.task.application;

import com.bifos.assistant.proactive.application.CheckNotificationPolicy;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 매일 깨우기가 조용한 시간에 만든 승인 알림을 억제한다. */
@Component
@RequiredArgsConstructor
public class ProactiveQuietHours implements CheckNotificationPolicy {

    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(7, 0);

    private final ProactiveCheckGuard checks;
    private final TaskRepository tasks;
    private final TaskTriggerRepository triggers;

    @Override
    public boolean allows(AgentExecution origin, Instant now) {
        return checks.checkOf(origin)
                .map(check -> {
                    if (check.trigger() != CheckTrigger.SCHEDULED) {
                        return true;
                    }
                    return scheduleOf(check.userId(), check.agentId())
                            .map(Task::id)
                            .flatMap(triggers::findByTaskId)
                            .map(trigger -> outsideQuietHours(trigger, now))
                            .orElse(false);
                })
                .orElse(true);
    }

    private Optional<Task> scheduleOf(Long userId, Long agentId) {
        return tasks.findByOwnerUserIdAndAgentIdAndKind(userId, agentId, TaskKind.CHECK)
                .filter(task -> !task.archived());
    }

    private static boolean outsideQuietHours(TaskTrigger trigger, Instant now) {
        LocalTime localTime = now.atZone(trigger.zone()).toLocalTime();
        return !localTime.isBefore(QUIET_END) && localTime.isBefore(QUIET_START);
    }
}
