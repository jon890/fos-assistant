package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.ProactiveCheckReadiness;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.ProactiveScheduleProperties;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.application.TaskProperties;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProactiveScheduleServiceTest {

    private final AgentService agents = mock(AgentService.class);
    private final ProactiveCheckReadiness readiness = mock(ProactiveCheckReadiness.class);
    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskTriggerRepository triggers = mock(TaskTriggerRepository.class);
    private final ProactiveCheckRepository checks = mock(ProactiveCheckRepository.class);
    private final Agent agent = mock(Agent.class);
    private final CurrentUser user = new CurrentUser(10L, "owner@example.com", "주인", 1L, UserRole.MEMBER);

    @Test
    @DisplayName("지원하지 않는 에이전트의 일정 조회는 Hermes 도구 목록을 호출하지 않는다")
    void unsupportedAgentReturnsBlockerWithoutHermesCalls() {
        when(agents.requireReadable(user, "connector")).thenReturn(agent);
        when(readiness.check(agent))
                .thenReturn(new CheckReadiness(List.of(CheckBlocker.of(CheckBlockerCode.AGENT_NOT_SUPPORTED))));

        assertThat(service(false).get(user, "connector").blockers())
                .containsExactly(CheckBlocker.of(CheckBlockerCode.AGENT_NOT_SUPPORTED));
        verifyNoInteractions(toolsets);
    }

    @Test
    @DisplayName("격리 실행 공간이 없고 터미널 도구가 켜져 있으면 매일 깨우기를 켜지 못한다")
    void rejectsEnablingScheduleWhenTerminalIsEnabledWithoutIsolation() {
        ProactiveScheduleService service = service(false);
        when(agents.requireStartable(user, "career")).thenReturn(agent);
        when(agent.apiBaseUrl()).thenReturn("http://agent-runtime.test");
        when(agent.hermesProfile()).thenReturn("career");
        when(readiness.check(agent)).thenReturn(new CheckReadiness(List.of()));
        when(toolsets.readEnabled("http://agent-runtime.test", "career")).thenReturn(List.of("terminal"));

        assertThatThrownBy(() -> service.update(user, "career", true, "09:00", "Asia/Seoul"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE);

        verify(tasks, never()).save(any());
        verifyNoInteractions(triggers, checks);
    }

    @Test
    @DisplayName("격리 실행 공간이 없을 때 터미널 도구는 화면에 막는 까닭과 함께 보인다")
    void reportsTerminalBlockerWhenIsolationIsDisabled() {
        ProactiveScheduleService service = service(false);
        when(agents.requireReadable(user, "career")).thenReturn(agent);
        when(agent.apiBaseUrl()).thenReturn("http://agent-runtime.test");
        when(agent.hermesProfile()).thenReturn("career");
        when(readiness.check(agent)).thenReturn(new CheckReadiness(List.of()));
        when(toolsets.readEnabled("http://agent-runtime.test", "career")).thenReturn(List.of("web", "terminal"));

        assertThat(service.get(user, "career").schedulingAvailable()).isFalse();
        assertThat(service.get(user, "career").blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.code().name()).isEqualTo("ISOLATED_EXECUTION_REQUIRED");
            assertThat(blocker.toolsets()).containsExactly("terminal");
        });
    }

    private ProactiveScheduleService service(boolean isolatedExecutionEnabled) {
        return new ProactiveScheduleService(
                agents,
                readiness,
                toolsets,
                new ProactiveScheduleProperties(isolatedExecutionEnabled),
                tasks,
                triggers,
                checks,
                new TaskProperties(
                        "-",
                        java.time.Duration.ofMinutes(2),
                        java.time.Duration.ofMinutes(10),
                        10,
                        java.time.Duration.ofMinutes(15),
                        48,
                        ZoneOffset.UTC.getId()),
                Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));
    }
}
