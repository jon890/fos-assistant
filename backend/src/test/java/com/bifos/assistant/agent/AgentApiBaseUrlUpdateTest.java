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

import com.bifos.assistant.agent.application.AgentAdminService;
import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentProperties;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.agent.application.ProfileProvisioning;
import com.bifos.assistant.agent.application.PeopleProperties;
import com.bifos.assistant.agent.application.ReservedProfileNames;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
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

/** 관리 화면이 에이전트를 등록하고 Hermes 주소를 고치는 길을 검사한다. */
class AgentApiBaseUrlUpdateTest {

    private static final String CURRENT_URL = "http://127.0.0.1:1/p/dad";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final HermesToolsetClient hermesToolsets = mock(HermesToolsetClient.class);
    private final AgentEndpointProbe endpointProbe = mock(AgentEndpointProbe.class);
    private final KnownFlows flows = mock(KnownFlows.class);

    /** 그룹 공개 검사는 실제 서비스가 한다. 도구 목록을 읽는 대역만 이 테스트가 정한다. */
    private final AgentLifecycleService lifecycle = new AgentLifecycleService(
            agents,
            mock(AgentService.class),
            users,
            mock(ReservedProfileNames.class),
            mock(ProfileProvisioning.class),
            hermesToolsets,
            mock(HermesProperties.class),
            mock(PeopleProperties.class),
            mock(AgentProperties.class),
            mock(ProfileSkillFiles.class),
            Clock.systemUTC());

    private final AgentAdminController controller = new AgentAdminController(
            new AgentAdminService(agents, users, lifecycle, endpointProbe, flows, Clock.systemUTC()), currentUser);

    private Agent agent;

    @BeforeEach
    void seed() {
        agent = Agent.of(
                "dad",
                "Dad",
                "dad",
                CURRENT_URL,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now());
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        when(agents.save(any(Agent.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("주소를 바꿔 저장하면 그 값이 남는다")
    void persistsUpdatedBaseUrl() {
        AdminAgentView view = controller.update("dad", request("http://127.0.0.1:2/p/dad"));

        assertThat(view.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
        assertThat(agent.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
        verify(agents).save(agent);
    }

    @Test
    @DisplayName("주소를 비워 보내면 지금 값이 그대로 남는다")
    void keepsCurrentUrlWhenBlankSent() {
        controller.update("dad", request(null));
        controller.update("dad", request("   "));

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        verify(endpointProbe, never()).requireReachable(anyString(), anyString());
    }

    @Test
    @DisplayName("끝에 슬래시를 붙여 보내면 떼고 저장한다")
    void stripsTrailingSlashBeforeSaving() {
        controller.update("dad", request("http://127.0.0.1:2/p/dad/"));

        assertThat(agent.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
    }

    @Test
    @DisplayName("확인이 실패하면 저장하지 않고 값이 그대로다")
    void keepsValueWhenProbeFails() {
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "the new address answered 404"))
                .when(endpointProbe)
                .requireReachable(anyString(), anyString());

        assertThatThrownBy(() -> controller.update("dad", request("http://127.0.0.1:2/p/dad")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("404");

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("확인은 그 에이전트의 profile 로 나간다")
    void sendsProbeWithAgentProfile() {
        controller.update("dad", request("http://127.0.0.1:2/p/dad"));

        verify(endpointProbe).requireReachable("http://127.0.0.1:2/p/dad", "dad");
    }

    @Test
    @DisplayName("그룹 공개 전에 도구를 읽지 못하면 접근 범위를 바꾸지 않는다")
    void keepsAccessScopeWhenToolsUnreadableBeforeGroupPublish() {
        agent = Agent.of(
                "dad",
                "Dad",
                "dad",
                CURRENT_URL,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L,
                Instant.now());
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "invalid toolset response"))
                .when(hermesToolsets)
                .readEnabled(CURRENT_URL, "dad");

        assertThatThrownBy(() -> controller.update("dad", request(null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);

        assertThat(agent.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        verify(agents, never()).save(agent);
    }

    @Test
    @DisplayName("그룹 공개와 주소 변경을 함께 보내도 새 주소의 도구를 검사한다")
    void checksToolsOfNewUrlWhenPublishingToGroupAndChangingUrl() {
        agent = Agent.of(
                "dad",
                "Dad",
                "dad",
                CURRENT_URL,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L,
                Instant.now());
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        when(hermesToolsets.readEnabled("http://127.0.0.1:2/p/dad", "dad")).thenReturn(List.of("terminal"));

        assertThatThrownBy(() -> controller.update("dad", request("http://127.0.0.1:2/p/dad")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        assertThat(agent.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        verify(agents, never()).save(agent);
    }

    @Test
    @DisplayName("그룹 에이전트를 새로 만들 때도 private toolset을 거절한다")
    void rejectsPrivateToolsetWhenCreatingGroupAgent() {
        when(agents.findByCode("group")).thenReturn(Optional.empty());
        when(hermesToolsets.readEnabled("http://127.0.0.1:2/p/group", "group-profile"))
                .thenReturn(List.of("terminal"));

        assertThatThrownBy(() -> controller.create(new CreateAgentRequest(
                        "group",
                        "Group",
                        "group-profile",
                        "http://127.0.0.1:2/p/group",
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.GROUP,
                        null,
                        null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("provider 없이 자기만 보는 에이전트를 등록하면 주소만 확인하고 저장한다")
    void onlyProbesUrlForPrivateAgentWithoutProvider() {
        when(agents.findByCode("mom")).thenReturn(Optional.empty());
        AppUser owner = owner(7L);
        when(users.findByEmail("mom@example.com")).thenReturn(Optional.of(owner));

        AdminAgentView view = controller.create(privateRequest("mom", "http://127.0.0.1:2/p/mom/"));

        assertThat(view)
                .extracting(
                        AdminAgentView::code,
                        AdminAgentView::apiBaseUrl,
                        AdminAgentView::visibility,
                        AdminAgentView::ownerUserId)
                .containsExactly("mom", "http://127.0.0.1:2/p/mom", "PRIVATE", 7L);
        verify(endpointProbe).requireReachable("http://127.0.0.1:2/p/mom/", "mom-profile");
        verify(agents).save(any(Agent.class));
        // 자기만 보는 에이전트는 도구를 검사하지 않는다. 등록이 Hermes 에 묻는 것은 주소 확인 하나뿐이다.
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("등록할 주소가 닿지 않으면 거절하고 저장하지 않는다")
    void rejectsUnreachableUrlOnRegister() {
        when(agents.findByCode("mom")).thenReturn(Optional.empty());
        AppUser owner = owner(7L);
        when(users.findByEmail("mom@example.com")).thenReturn(Optional.of(owner));
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "the new address answered 404 for /v1/capabilities"))
                .when(endpointProbe)
                .requireReachable("http://127.0.0.1:2/p/mom", "mom-profile");

        assertThatThrownBy(() -> controller.create(privateRequest("mom", "http://127.0.0.1:2/p/mom")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("그룹 에이전트를 끄는 요청은 도구를 읽지 않는다")
    void skipsToolReadWhenDisablingGroupAgent() {
        AdminAgentView view =
                controller.update("dad", new UpdateAgentRequest(false, AgentVisibility.GROUP, null, null));

        assertThat(view.enabled()).isFalse();
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("꺼진 그룹 에이전트를 켜기 전에는 private toolset을 검사한다")
    void checksPrivateToolsetBeforeEnablingDisabledGroupAgent() {
        agent.changeAccess(false, AgentVisibility.GROUP, null);
        when(hermesToolsets.readEnabled(CURRENT_URL, "dad")).thenReturn(List.of("terminal"));

        assertThatThrownBy(() -> controller.update("dad", request(null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agent.enabled()).isFalse();
        verify(agents, never()).save(agent);
    }

    private static CreateAgentRequest privateRequest(String code, String apiBaseUrl) {
        return new CreateAgentRequest(
                code,
                "Mom",
                code + "-profile",
                apiBaseUrl,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                code + "@example.com",
                null);
    }

    private static AppUser owner(Long id) {
        AppUser user = mock(AppUser.class);
        when(user.id()).thenReturn(id);
        return user;
    }

    private static UpdateAgentRequest request(String apiBaseUrl) {
        return new UpdateAgentRequest(true, AgentVisibility.GROUP, null, apiBaseUrl);
    }
}
