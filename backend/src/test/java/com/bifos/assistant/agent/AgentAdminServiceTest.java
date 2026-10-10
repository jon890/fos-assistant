package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.admin.application.AgentAdminService;
import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.admin.application.model.AgentCreateCommand;
import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.admin.application.model.AgentUpdateCommand;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리자가 에이전트를 등록하고 고치는 유스케이스의 결과와 검사 순서를 본다. */
class AgentAdminServiceTest {

    private static final String API_BASE_URL = "http://127.0.0.1:2/p/group";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    private final AgentEndpointProbe endpointProbe = mock(AgentEndpointProbe.class);
    private final KnownFlows flows = mock(KnownFlows.class);
    private final AgentConnectorBindings connectorBindings = mock(AgentConnectorBindings.class);

    private final AgentAdminService service =
            new AgentAdminService(agents, users, lifecycle, endpointProbe, flows, connectorBindings, Clock.systemUTC());

    @BeforeEach
    void setUp() {
        when(agents.save(any(Agent.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("그룹 공개 에이전트를 만들면 저장된 에이전트를 돌려준다")
    void returnsSavedAgentWhenCreatingGroupAgent() {
        when(agents.findByCode("group")).thenReturn(Optional.empty());

        Agent created = service.create(groupCommand());

        assertThat(created)
                .extracting(
                        Agent::code,
                        Agent::name,
                        Agent::hermesProfile,
                        Agent::apiBaseUrl,
                        Agent::visibility,
                        Agent::ownerUserId)
                .containsExactly("group", "Group", "group-profile", API_BASE_URL, AgentVisibility.GROUP, null);
        verify(agents).save(created);
        verify(endpointProbe).requireReachable(API_BASE_URL, "group-profile");
        verify(lifecycle).requireGroupSafe(API_BASE_URL, "group-profile");
    }

    @Test
    @DisplayName("이미 쓰는 코드면 VALIDATION FAILED 로 거절하고 주소가 닿는지 묻지 않는다")
    void rejectsUsedCodeBeforeProbingEndpoint() {
        when(agents.findByCode("group")).thenReturn(Optional.of(mock(Agent.class)));

        assertThatThrownBy(() -> service.create(groupCommand()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(endpointProbe);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("지운 에이전트는 관리 목록에서 빠진다")
    void leavesDeletedAgentsOutOfList() {
        Agent live = mock(Agent.class);
        Agent deleted = mock(Agent.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(agents.findAll()).thenReturn(List.of(live, deleted));

        assertThat(service.list()).containsExactly(live);
    }

    @Test
    @DisplayName("연결이 붙은 에이전트의 주인을 바꾸면 AGENT HAS CONNECTIONS 로 거절하고 저장하지 않는다")
    void rejectsOwnerChangeOfAgentWithConnections() {
        Agent agent = privateAgent(1L);
        AppUser newOwner = mock(AppUser.class);
        when(newOwner.id()).thenReturn(2L);
        when(users.findByEmail("new@example.com")).thenReturn(Optional.of(newOwner));
        when(connectorBindings.hasBindings(agent.id())).thenReturn(true);

        assertThatThrownBy(() -> service.update("dad", privateUpdate("new@example.com")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_HAS_CONNECTIONS);

        assertThat(agent.ownerUserId()).isEqualTo(1L);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("셸이나 파일 도구가 켜진 에이전트의 주인을 바꾸면 거절하고 주인을 그대로 둔다")
    void rejectsOwnerChangeWhenShellToolsetEnabled() {
        Agent agent = privateAgent(1L);
        AppUser next = mock(AppUser.class);
        when(next.id()).thenReturn(2L);
        when(users.findByEmail("next@example.com")).thenReturn(Optional.of(next));
        doThrow(new ApiException(ErrorCode.AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF, "shell is on"))
                .when(lifecycle)
                .requireOwnerChangeSafe(API_BASE_URL, "dad-profile");

        assertThatThrownBy(() -> service.update("dad", privateUpdate("next@example.com")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF);

        assertThat(agent.ownerUserId()).isEqualTo(1L);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("연결이 붙은 에이전트를 그룹으로 바꾸면 AGENT CONNECTIONS REQUIRE PRIVATE 로 거절한다")
    void rejectsGroupVisibilityOfAgentWithConnections() {
        Agent agent = privateAgent(1L);
        when(connectorBindings.hasBindings(agent.id())).thenReturn(true);

        assertThatThrownBy(() ->
                        service.update("dad", new AgentUpdateCommand(true, AgentVisibility.GROUP, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE);

        assertThat(agent.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        verify(agents, never()).save(any(Agent.class));
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("연결이 붙은 에이전트도 주인과 공개 범위를 그대로 두는 수정은 저장한다")
    void savesUpdateKeepingOwnerOfAgentWithConnections() {
        Agent agent = privateAgent(1L);
        when(connectorBindings.hasBindings(agent.id())).thenReturn(true);

        Agent updated = service.update("dad", new AgentUpdateCommand(false, AgentVisibility.PRIVATE, null, null, null));

        assertThat(updated.enabled()).isFalse();
        assertThat(updated.ownerUserId()).isEqualTo(1L);
        verify(agents).save(agent);
    }

    @Test
    @DisplayName("주인이 그대로인 접근 변경은 셸 도구가 켜져 있어도 저장한다")
    void keepsAccessChangeWithSameOwner() {
        Agent agent = privateAgent(1L);
        doThrow(new ApiException(ErrorCode.AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF, "shell is on"))
                .when(lifecycle)
                .requireOwnerChangeSafe(anyString(), anyString());

        Agent saved = service.update("dad", new AgentUpdateCommand(false, AgentVisibility.PRIVATE, null, null, null));

        assertThat(saved.enabled()).isFalse();
        assertThat(saved.ownerUserId()).isEqualTo(1L);
        verify(agents).save(agent);
    }

    private Agent privateAgent(Long ownerId) {
        Agent agent = Agent.of(
                "dad",
                "Dad",
                "dad-profile",
                API_BASE_URL,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                ownerId,
                Instant.now());
        when(agents.findByCodeForUpdate("dad")).thenReturn(Optional.of(agent));
        return agent;
    }

    private static AgentUpdateCommand privateUpdate(String ownerEmail) {
        return new AgentUpdateCommand(true, AgentVisibility.PRIVATE, ownerEmail, null, null);
    }

    private static AgentCreateCommand groupCommand() {
        return new AgentCreateCommand(
                "group",
                "Group",
                "group-profile",
                API_BASE_URL,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                null);
    }
}
