package com.bifos.assistant.task.application;

import com.bifos.assistant.proactive.check.application.port.CheckNotificationPolicy;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 매일 깨우기가 조용한 시간에 만든 승인 알림을 억제한다. 조용한 시간의 값은 {@link #quiet} 가 갖는다. */
@Component
@RequiredArgsConstructor
public class ProactiveQuietHours implements CheckNotificationPolicy {

    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(7, 0);

    private final ProactiveCheckGuard checks;
    private final TaskRepository tasks;


    @Override
    public boolean allows(AgentExecution origin, Instant now) {
        return checks.checkOf(origin)
            .map(check -> {
                if (check.trigger() != CheckTrigger.SCHEDULED) {
                    return true;
                }
                return scheduleOf(check.userId(), check.agentId())
                    .map(task -> outsideQuietHours(task, now))
                    .orElse(false);
            })
            .orElse(true);
    }


    private Optional<Task> scheduleOf(Long userId, Long agentId) {
        return tasks.findByOwnerUserIdAndAgentIdAndKind(userId, agentId, TaskKind.CHECK)
            .filter(task -> !task.archived());
    }


    private static boolean outsideQuietHours(Task task, Instant now) {
        return !quiet(task.zone(), now);
    }


    /** 그 시간대의 22시부터 07시 전까지면 참이다. 사건 감지의 알림 미루기(ADR-20261105 / event-watch)도 같은 시간을 쓴다. */
    public static boolean quiet(ZoneId zone, Instant now) {
        LocalTime localTime = now.atZone(zone).toLocalTime();
        return localTime.isBefore(QUIET_END) || !localTime.isBefore(QUIET_START);
    }
}
