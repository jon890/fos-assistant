package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentSandboxSweep;
import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.application.port.HermesRequestRejected;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 기동할 때 실행 공간 도구가 켜진 에이전트를 다시 저장하고, 정책 밖이라고 답한 profile 만 그 도구를 끄는지 본다
 * (ADR-20261120 / no-local-shell, 스펙 agent-tools REQ1).
 */
@BackendIntegrationTest
class AgentSandboxSweepTest {

    private static final long GROUP = 7_401L;
    private static final String LISTED = "sweep-listed";
    private static final String UNLISTED = "sweep-unlisted";
    private static final String BROKEN = "sweep-broken";
    private static final String SHELL_OFF = "sweep-off";
    private static final String GROUP_AGENT = "sweep-group";
    private static final String OWNED_GROUP = "sweep-owned-group";

    @Autowired
    AgentSandboxSweep sweep;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    AgentConnectorBindings connectorBindings;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser owner;
    private Agent groupAgent;
    private Agent unlisted;
    private Agent broken;


    @BeforeEach
    void setUp() {
        reset(toolsets, connectorBindings);
        when(connectorBindings.connectorServers(any())).thenReturn(Set.of());
        jdbc.update("DELETE FROM agent WHERE hermes_profile LIKE 'sweep-%'");
        jdbc.update("DELETE FROM app_user WHERE group_id = ?", GROUP);
        owner = users.save(AppUser.of("sweep-owner@example.com", "지윤", GROUP, UserRole.MEMBER));
        for (String profile : List.of(LISTED, SHELL_OFF)) {
            agents.save(agent(profile, AgentVisibility.PRIVATE, owner.id()));
        }
        unlisted = agents.save(agent(UNLISTED, AgentVisibility.PRIVATE, owner.id()));
        broken = agents.save(agent(BROKEN, AgentVisibility.PRIVATE, owner.id()));
        groupAgent = agents.save(agent(GROUP_AGENT, AgentVisibility.GROUP, null));
        agents.save(agent(OWNED_GROUP, AgentVisibility.GROUP, owner.id()));
        // 배포 전에 있던 에이전트처럼 한 번 점검할 표시를 켠다. 마이그레이션이 하는 일이다.
        jdbc.update("UPDATE agent SET sandbox_check_pending = TRUE WHERE hermes_profile LIKE 'sweep-%'");
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
        when(toolsets.readEnabled(anyString(), eq(LISTED))).thenReturn(List.of("web", "terminal", "fos-attachments"));
        when(toolsets.readEnabled(anyString(), eq(UNLISTED))).thenReturn(List.of("web", "terminal", "file"));
        when(toolsets.readEnabled(anyString(), eq(BROKEN))).thenReturn(List.of("code_execution"));
        when(toolsets.readEnabled(anyString(), eq(SHELL_OFF))).thenReturn(List.of("web"));
        when(toolsets.readEnabled(anyString(), eq(GROUP_AGENT))).thenReturn(List.of("terminal"));
        when(toolsets.readEnabled(anyString(), eq(OWNED_GROUP))).thenReturn(List.of("web", "terminal"));
        doThrow(rejected(true)).when(toolsets).writeApiServerInSandbox(eq(UNLISTED), anyList(), anyString());
        doThrow(rejected(false)).when(toolsets).writeApiServerInSandbox(eq(BROKEN), anyList(), anyString());
    }


    @Test
    @DisplayName("정책 안은 같은 목록을 실행 공간 쓰기로, 정책 밖은 실행 공간 도구를 뺀 목록으로 다시 쓰고 셸이 꺼진 에이전트는 쓰지 않는다")
    void rewritesListedAndRemovesShellOnlyForUnlisted() {
        sweep.sweepAll();

        verify(toolsets)
            .writeApiServerInSandbox(LISTED, List.of("web", "terminal", "fos-assistant", "fos-attachments"), "u" + owner.id());
        verify(toolsets).writeApiServer(UNLISTED, List.of("web", "fos-assistant"), "u" + owner.id());
        verify(toolsets, never()).writeApiServerInSandbox(eq(SHELL_OFF), anyList(), anyString());
        verify(toolsets, never()).writeApiServer(eq(SHELL_OFF), anyList(), anyString());
    }


    @Test
    @DisplayName("셸을 끌 때 붙은 플러그인 서버 이름과 Control Plane MCP 는 남긴다")
    void keepsBoundConnectorServersWhenRemovingShell() {
        when(connectorBindings.connectorServers(unlisted.id())).thenReturn(Set.of("naver-blog"));

        sweep.sweepAll();

        verify(toolsets).writeApiServer(UNLISTED, List.of("web", "fos-assistant", "naver-blog"), "u" + owner.id());
    }


    @Test
    @DisplayName("정책 밖 표시가 없는 거절은 아무것도 끄지 않고 다음 차례에 다시 본다")
    void keepsShellAndRetriesWhenTheRejectionIsNotUnlisted() {
        sweep.sweepAll();

        verify(toolsets, never()).writeApiServer(eq(BROKEN), anyList(), anyString());
        assertThat(sweep.unfinished()).contains(broken.id());
        assertThat(pending(BROKEN)).as("끝내지 못한 에이전트는 점검 표시를 남긴다").isTrue();

        doNothing().when(toolsets).writeApiServerInSandbox(eq(BROKEN), anyList(), anyString());
        sweep.retryUnfinished();

        // 첫 차례의 거절된 시도와 다시 시도가 같은 쓰기다.
        verify(toolsets, times(2))
            .writeApiServerInSandbox(BROKEN, List.of("code_execution", "fos-assistant"), "u" + owner.id());
        assertThat(sweep.unfinished()).doesNotContain(broken.id());
        assertThat(pending(BROKEN)).isFalse();
    }


    @Test
    @DisplayName("한 에이전트의 Hermes 실패가 다음 에이전트를 막지 않고, 그룹 공개이거나 주인이 없어도 다시 쓴다")
    void continuesAfterAFailureAndDoesNotSkipGroupAgents() {
        when(toolsets.readEnabled(anyString(), eq(LISTED))).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        sweep.sweepAll();

        verify(toolsets).writeApiServerInSandbox(GROUP_AGENT, List.of("terminal", "fos-assistant"), "a" + groupAgent.id());
        verify(toolsets).writeApiServer(UNLISTED, List.of("web", "fos-assistant"), "u" + owner.id());
    }


    @Test
    @DisplayName("점검을 끝낸 뒤에는 정책 밖이라고 답해도 셸을 끄지 않는다")
    void doesNotRemoveShellAfterTheOneTimeCheck() {
        sweep.sweepAll();
        assertThat(pending(UNLISTED)).isFalse();
        reset(toolsets);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
        when(toolsets.readEnabled(anyString(), eq(UNLISTED))).thenReturn(List.of("web", "terminal"));
        doThrow(rejected(true)).when(toolsets).writeApiServerInSandbox(eq(UNLISTED), anyList(), anyString());

        sweep.sweepAll();

        verify(toolsets, never()).writeApiServer(eq(UNLISTED), anyList(), anyString());
        assertThat(sweep.unfinished()).doesNotContain(unlisted.id());
    }


    @Test
    @DisplayName("주인이 있는 그룹 공개 에이전트의 셸은 한 번 점검에서 끄고 실행 공간 쓰기를 하지 않는다")
    void removesShellFromGroupAgentWithOwner() {
        sweep.sweepAll();

        verify(toolsets).writeApiServer(OWNED_GROUP, List.of("web", "fos-assistant"), "u" + owner.id());
        verify(toolsets, never()).writeApiServerInSandbox(eq(OWNED_GROUP), anyList(), anyString());
    }


    private boolean pending(String profile) {
        return Boolean.TRUE.equals(
            jdbc.queryForObject("SELECT sandbox_check_pending FROM agent WHERE hermes_profile = ?", Boolean.class, profile));
    }


    private static HermesRequestRejected rejected(boolean unlisted) {
        return new HermesRequestRejected(
            ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "no sandbox", new HttpClientErrorException(HttpStatus.CONFLICT), unlisted);
    }


    private static Agent agent(String profile, AgentVisibility visibility, Long ownerId) {
        return Agent.of(
            profile,
            "비서",
            profile,
            "http://listener.test/p/" + profile,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            visibility,
            ownerId);
    }
}
