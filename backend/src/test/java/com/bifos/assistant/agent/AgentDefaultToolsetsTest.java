package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.application.toolset.AgentDefaultToolsets;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기본 도구를 켤 때 셸 계열이 실행 공간 없이 켜지지 않는지 본다. */
class AgentDefaultToolsetsTest {

    private static final List<String> DEFAULTS = List.of("web", "terminal", "file", "code_execution");

    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final ProfileSkillFiles skillFiles = mock(ProfileSkillFiles.class);
    private final AgentConnectorBindings connectorBindings = mock(AgentConnectorBindings.class);
    private final AgentDefaultToolsets service = new AgentDefaultToolsets(toolsets, skillFiles, connectorBindings);
    private final Agent agent = agent(AgentVisibility.PRIVATE);

    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("delegation"));
        when(connectorBindings.connectorServers(any())).thenReturn(Set.of());
        when(skillFiles.uploadedRequestingSecrets(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("실행 공간이 있으면 지금 도구에 기본 도구를 더해 local 금지 쓰기로 쓴다")
    void writesDefaultsInSandboxWhenRegistered() {
        List<String> written = service.apply(agent, DEFAULTS);

        List<String> expected = List.of("delegation", "web", "terminal", "file", "code_execution", "fos-assistant");
        assertThat(written).isEqualTo(expected);
        verify(toolsets).writeApiServerInSandbox(agent.hermesProfile(), expected, "u1");
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("실행 공간이 없으면 셸 계열을 빼고 web 만 더한다")
    void dropsShellToolsetsWhenSandboxUnavailable() {
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "no sandbox"))
                .when(toolsets)
                .writeApiServerInSandbox(anyString(), anyList(), anyString());

        List<String> written = service.apply(agent, DEFAULTS);

        assertThat(written).containsExactly("delegation", "web", "fos-assistant");
        verify(toolsets).writeApiServer(agent.hermesProfile(), written, "u1");
    }

    @Test
    @DisplayName("셸 계열이 이미 켜진 profile 은 실행 공간이 없을 때 다시 쓰지 않는다")
    void skipsFallbackWhenShellAlreadyEnabledWithoutSandbox() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("terminal"));
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "no sandbox"))
                .when(toolsets)
                .writeApiServerInSandbox(anyString(), anyList(), anyString());

        assertThat(service.apply(agent, DEFAULTS)).isEmpty();

        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("실행 공간이 아닌 까닭의 거절이면 셸 계열을 뺀 쓰기로 넘어가지 않는다")
    void doesNotFallBackOnOtherRejections() {
        doThrow(new ApiException(ErrorCode.CONNECTOR_OPERATION_FAILED, "connector server missing"))
                .when(toolsets)
                .writeApiServerInSandbox(anyString(), anyList(), anyString());

        assertThat(service.apply(agent, DEFAULTS)).isEmpty();

        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("셸 계열이 없는 기본값은 local 금지 칸 없이 쓴다")
    void writesWithoutSandboxRequirementWhenNoShellToolset() {
        service.apply(agent, List.of("web"));

        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("delegation", "web", "fos-assistant"), "u1");
        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("기본값이 비었으면 Hermes 를 부르지 않는다")
    void doesNothingWhenDefaultsAreEmpty() {
        assertThat(service.apply(agent, List.of())).isEmpty();

        verifyNoInteractions(toolsets);
    }

    @Test
    @DisplayName("이미 다 켜져 있으면 쓰지 않는다")
    void skipsWriteWhenAlreadyEnabled() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(DEFAULTS);

        assertThat(service.apply(agent, DEFAULTS)).isEmpty();

        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("Hermes 가 실패해도 예외를 올리지 않는다")
    void swallowsHermesFailure() {
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))
                .when(toolsets)
                .writeApiServerInSandbox(anyString(), anyList(), anyString());

        assertThat(service.apply(agent, DEFAULTS)).isEmpty();

        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("그룹 공개 에이전트에는 셸 계열을 켜지 않는다")
    void dropsPrivateOnlyToolsetsForGroupAgent() {
        Agent group = agent(AgentVisibility.GROUP);
        when(toolsets.readEnabled(group.apiBaseUrl(), group.hermesProfile())).thenReturn(List.of("delegation"));

        service.apply(group, DEFAULTS);

        verify(toolsets).writeApiServer(group.hermesProfile(), List.of("delegation", "web", "fos-assistant"), "u1");
    }

    @Test
    @DisplayName("비밀을 요청하는 올린 스킬이 있으면 셸 계열을 켜지 않는다")
    void dropsShellToolsetsWhenSkillRequestsSecrets() {
        when(skillFiles.uploadedRequestingSecrets(agent.hermesProfile())).thenReturn(List.of("leaky"));

        service.apply(agent, DEFAULTS);

        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("delegation", "web", "fos-assistant"), "u1");
        verify(toolsets, never()).writeApiServerInSandbox(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("붙은 커넥터 서버 이름을 목록 끝에 함께 보낸다")
    void keepsConnectorServersInWrittenList() {
        when(connectorBindings.connectorServers(any())).thenReturn(Set.of("gmail"));

        service.apply(agent, List.of("web"));

        verify(toolsets)
                .writeApiServer(agent.hermesProfile(), List.of("delegation", "web", "fos-assistant", "gmail"), "u1");
    }

    private static Agent agent(AgentVisibility visibility) {
        return Agent.of(
                "basic",
                "기본",
                "basic",
                "http://listener.test/p/basic",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                1L,
                Instant.now());
    }
}
