package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.task.application.EventWatchService;
import com.bifos.assistant.task.presentation.EventWatchController;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 에이전트 상세의 사건 감지 조회와 켜고 끄기 계약이다(ADR-20261105 / event-watch). 요구사항은 {@code docs/features/proactive/spec.md} 의
 * REQ7, REQ9, REQ10, REQ11 의 화면 쪽 값이다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
class EventWatchControllerTest extends EventWatchTestSupport {

    private static final String PATH = "/api/v1/agents/{code}/event-watches";

    @Autowired
    EventWatchService service;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private CurrentUser stranger;
    private MockMvc mvc;


    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.standaloneSetup(new EventWatchController(currentUser, service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        when(currentUser.require()).thenAnswer(invocation -> user);
    }


    @AfterEach
    void unbindStranger() {
        if (stranger == null) {
            return;
        }
        jdbc.update("DELETE FROM agent_connector_binding WHERE connection_id IN"
            + " (SELECT id FROM connector_connection WHERE user_id = ?)", stranger.id());
        jdbc.update("DELETE FROM connector_connection WHERE user_id = ?", stranger.id());
    }


    @Test
    @DisplayName("붙인 직후에는 꺼져 있고, 켜면 시간대를 저장하며 비우면 서울이고, 다시 켜면 시간대만 바꾼다 (REQ10)")
    void defaultOffEnableAndReenable() throws Exception {
        watches.disable(user.id(), agent.id(), CONNECTOR);

        mvc.perform(get(PATH, agent.code()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].connectorId").value(CONNECTOR))
            .andExpect(jsonPath("$[0].title").value("메모"))
            .andExpect(jsonPath("$[0].noun").value("메모"))
            .andExpect(jsonPath("$[0].enabled").value(false))
            .andExpect(jsonPath("$[0].available").value(true))
            .andExpect(jsonPath("$[0].timezone").value("Asia/Seoul"));

        save(true, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.timezone").value("Asia/Seoul"));
        Instant checkpoint = state().checkpoint();
        clock.set(clock.instant().plus(Duration.ofHours(1)));

        save(true, "UTC").andExpect(jsonPath("$.timezone").value("UTC"));
        assertThat(state().checkpoint()).as("다시 켜면 지점을 그대로 둔다").isEqualTo(checkpoint);
        save(true, "Mars/Base")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TASK_SCHEDULE_INVALID"));

        save(false, "").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject("SELECT event_watch_state FROM agent_connector_binding WHERE id = ?", String.class,
            binding.id())).isNull();
    }


    @Test
    @DisplayName("격리 설정이 꺼진 동안 셸 toolset 이 켜졌거나 위임 toolset 이 켜진 에이전트는 켜지 못하고 조회가 까닭을 싣는다 (REQ9)")
    void shellAndDelegationToolsetsBlockEnabling() throws Exception {
        watches.disable(user.id(), agent.id(), CONNECTOR);

        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "terminal"));
        expectBlocked(agent, "ISOLATED_EXECUTION_REQUIRED");
        mvc.perform(get(PATH, agent.code())).andExpect(jsonPath("$[0].blockers[0].toolsets[0]").value("terminal"));

        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "delegation"));
        expectBlocked(agent, "TOOLSETS_NOT_ALLOWED");
    }


    @Test
    @DisplayName("조회 도구에 승인이 필요하거나, 같은 연결의 다른 에이전트가 켰거나, 바인딩이 준비되지 않았으면 켜지 못한다 (REQ10)")
    void connectionConditionsBlockEnabling() throws Exception {
        Agent other = newAgent(user);
        bind(other);
        expectBlocked(other, "EVENT_WATCH_ELSEWHERE");

        Agent pending = newAgent(user);
        ConnectorBinding waiting = ConnectorBinding.pending(pending, binding.connection(), "notes", clock.instant());
        bindings.save(waiting);
        expectBlocked(pending, "CONNECTION_NOT_READY");

        watches.disable(user.id(), agent.id(), CONNECTOR);
        when(connector.readCatalog()).thenReturn(List.of(watchToolApproval("required")));
        ConnectorCatalogTimes.expire();
        expectBlocked(agent, "EVENT_TOOL_NOT_ALLOWED");
    }


    @Test
    @DisplayName("오늘 실행 수가 상한이거나 오늘 마지막 확인이 상한이면 상한 도달이고, 어제의 상한은 아니다 (REQ7)")
    void dailyLimitReachedIsShown() throws Exception {
        Instant now = clock.instant();
        writeState(state -> withRuns(state, 12, null, null));
        expectFlag("dailyLimitReached", true);

        writeState(state -> withRuns(state, 3, EventWatchResult.DAILY_LIMIT, now));
        expectFlag("dailyLimitReached", true);

        writeState(state -> withRuns(state, 3, EventWatchResult.DAILY_LIMIT, now.minus(Duration.ofDays(1))));
        expectFlag("dailyLimitReached", false);
    }


    @Test
    @DisplayName("연결 문제의 연속 실패가 정한 횟수에 닿으면 다시 연결이 필요하다고 보인다 (REQ11)")
    void failingIsShown() throws Exception {
        writeState(state -> state.failed(clock.instant(), true).failed(clock.instant(), true).failed(clock.instant(), true));
        expectFlag("failing", false);

        writeState(state -> state.failed(clock.instant(), true));
        expectFlag("failing", true);
    }


    @Test
    @DisplayName("Hermes 장애 중에는 조회가 확인하지 못했다고 보이고 끄기는 되며, 켜기는 그 오류로 거절한다")
    void runtimeOutageKeepsReadAndDisable() throws Exception {
        when(toolsets.readEnabled(anyString(), anyString()))
            .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "runtime went away"));

        mvc.perform(get(PATH, agent.code()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].available").value(false))
            .andExpect(jsonPath("$[0].blockers[0].code").value("READINESS_UNKNOWN"));
        save(false, "").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        save(true, "").andExpect(jsonPath("$.code").value("HERMES_UNAVAILABLE"));
    }


    @Test
    @DisplayName("켜짐 값이 빠진 요청은 끄기로 읽지 않고 거절한다")
    void missingEnabledIsRejected() throws Exception {
        mvc.perform(put(PATH + "/{connectorId}", agent.code(), CONNECTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"timezone\":\"Asia/Seoul\"}"))
            .andExpect(status().isBadRequest());
        assertThat(state()).as("사건 감지는 켜진 채다").isNotNull();
    }


    @Test
    @DisplayName("요청자가 읽을 수 있는 에이전트라도 다른 사용자의 연결은 싣지 않고 켜지 못하며, 실을 것이 없으면 Hermes 를 부르지 않는다")
    void otherUsersConnectionsAreInvisible() throws Exception {
        stranger = newUser();
        Agent shared = agents.save(Agent.of("loop-" + UUID.randomUUID().toString().substring(0, 8), "공유", "공유",
            "http://agent-runtime.test/p/shared", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.GROUP, stranger.id()));
        Instant now = clock.instant();
        ConnectorConnection theirs = ConnectorConnection.pending(stranger.id(), CONNECTOR, now);
        theirs.ready(now);
        ConnectorBinding bound = ConnectorBinding.pending(shared, connections.save(theirs), "notes", now);
        bound.installed(false, now);
        bound.ready(now);
        bindings.save(bound);

        clearInvocations(toolsets);
        mvc.perform(get(PATH, shared.code())).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        verify(toolsets, never()).readEnabled(anyString(), anyString());
        mvc.perform(put(PATH + "/{connectorId}", shared.code(), CONNECTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false,\"timezone\":\"\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CONNECTOR_NOT_FOUND"));
    }


    private ResultActions save(boolean enabled, String timezone) throws Exception {
        return mvc.perform(put(PATH + "/{connectorId}", agent.code(), CONNECTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":" + enabled + ",\"timezone\":\"" + timezone + "\"}"));
    }


    /** 켜기가 거절되고 조회가 그 까닭을 싣는다. */
    private void expectBlocked(Agent target, String code) throws Exception {
        mvc.perform(put(PATH + "/{connectorId}", target.code(), CONNECTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true,\"timezone\":\"Asia/Seoul\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROACTIVE_CHECK_UNAVAILABLE"));
        mvc.perform(get(PATH, target.code()))
            .andExpect(jsonPath("$[0].enabled").value(false))
            .andExpect(jsonPath("$[0].available").value(false))
            .andExpect(jsonPath("$[0].blockers[*].code", hasItem(code)));
    }


    private void expectFlag(String name, boolean value) throws Exception {
        mvc.perform(get(PATH, agent.code())).andExpect(jsonPath("$[0]." + name).value(value));
    }


    private void writeState(UnaryOperator<EventWatchState> change) {
        watches.update(binding.id(), change);
    }


    private EventWatchState withRuns(EventWatchState state, int runs, EventWatchResult result, Instant checkedAt) {
        return new EventWatchState(state.timeZone(), state.checkpoint(), state.seen(), checkedAt, result, 0, 0,
            clock.instant().atZone(SEOUL).toLocalDate(), runs, state.enabledAt(), false, null);
    }
}
