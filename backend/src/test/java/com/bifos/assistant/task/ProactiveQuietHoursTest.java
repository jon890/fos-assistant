package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.task.application.ProactiveQuietHours;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProactiveQuietHoursTest {

    private final ProactiveCheckGuard checks = mock(ProactiveCheckGuard.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskTriggerRepository triggers = mock(TaskTriggerRepository.class);
    private final AgentExecution origin = mock(AgentExecution.class);
    private final ProactiveCheck scheduled = mock(ProactiveCheck.class);
    private final Task schedule = mock(Task.class);
    private final TaskTrigger trigger = mock(TaskTrigger.class);

    @Test
    @DisplayName("설정 시간대의 22시부터 7시 전까지는 예약 점검 승인 알림을 억제한다")
    void suppressesScheduledCheckNotificationsDuringQuietHours() {
        quietSchedule(ZoneId.of("Asia/Seoul"));

        assertThat(policy().allows(origin, Instant.parse("2026-10-05T13:00:00Z"))).isFalse();
        assertThat(policy().allows(origin, Instant.parse("2026-10-05T21:59:59Z"))).isFalse();
    }

    @Test
    @DisplayName("7시부터 22시 전까지는 예약 점검 승인 알림을 허용한다")
    void allowsScheduledCheckNotificationsOutsideQuietHours() {
        quietSchedule(ZoneId.of("Asia/Seoul"));

        assertThat(policy().allows(origin, Instant.parse("2026-10-04T22:00:00Z"))).isTrue();
        assertThat(policy().allows(origin, Instant.parse("2026-10-05T12:59:59Z"))).isTrue();
    }

    @Test
    @DisplayName("수동 점검과 점검 밖 실행은 시간과 관계없이 알림을 허용한다")
    void allowsManualAndNonCheckOrigins() {
        when(checks.checkOf(origin)).thenReturn(Optional.of(scheduled));
        when(scheduled.trigger()).thenReturn(CheckTrigger.MANUAL);

        assertThat(policy().allows(origin, Instant.parse("2026-10-05T13:00:00Z"))).isTrue();

        when(checks.checkOf(origin)).thenReturn(Optional.empty());
        assertThat(policy().allows(origin, Instant.parse("2026-10-05T13:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("다른 사용자의 설정이나 없는 설정으로는 예약 점검 알림을 허용하지 않는다")
    void suppressesWhenOwnersScheduleIsMissing() {
        when(checks.checkOf(origin)).thenReturn(Optional.of(scheduled));
        when(scheduled.trigger()).thenReturn(CheckTrigger.SCHEDULED);
        when(scheduled.userId()).thenReturn(101L);
        when(scheduled.agentId()).thenReturn(201L);
        when(tasks.findByOwnerUserIdAndAgentIdAndKind(101L, 201L, TaskKind.CHECK)).thenReturn(Optional.empty());

        assertThat(policy().allows(origin, Instant.parse("2026-10-05T10:00:00Z"))).isFalse();
        verify(tasks).findByOwnerUserIdAndAgentIdAndKind(101L, 201L, TaskKind.CHECK);
    }

    private void quietSchedule(ZoneId zone) {
        when(checks.checkOf(origin)).thenReturn(Optional.of(scheduled));
        when(scheduled.trigger()).thenReturn(CheckTrigger.SCHEDULED);
        when(scheduled.userId()).thenReturn(101L);
        when(scheduled.agentId()).thenReturn(201L);
        when(tasks.findByOwnerUserIdAndAgentIdAndKind(101L, 201L, TaskKind.CHECK)).thenReturn(Optional.of(schedule));
        when(schedule.archived()).thenReturn(false);
        when(schedule.id()).thenReturn(301L);
        when(triggers.findByTaskId(301L)).thenReturn(Optional.of(trigger));
        when(trigger.zone()).thenReturn(zone);
    }

    private ProactiveQuietHours policy() {
        return new ProactiveQuietHours(checks, tasks, triggers);
    }
}
