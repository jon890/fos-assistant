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

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 관리 화면이 에이전트를 등록하고 Hermes 주소를 고치는 길을 검사한다. */
class AgentApiBaseUrlUpdateTest {

    private static final String CURRENT_URL = "http://127.0.0.1:1/p/dad";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final HermesToolsetClient hermesToolsets = mock(HermesToolsetClient.class);
    private final AgentEndpointProbe endpointProbe = mock(AgentEndpointProbe.class);
    private final FlowRegistry flows = mock(FlowRegistry.class);

    private final AgentAdminController controller = new AgentAdminController(
            agents, users, currentUser, hermesToolsets, endpointProbe, flows);

    private Agent agent;

    @BeforeEach
    void seed() {
        agent = Agent.of("dad", "Dad", "dad", CURRENT_URL,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP, null);
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        when(agents.save(any(Agent.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void 주소를_바꿔_저장하면_그_값이_남는다() {
        AdminAgentView view = controller.update("dad", request("http://127.0.0.1:2/p/dad"));

        assertThat(view.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
        assertThat(agent.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
        verify(agents).save(agent);
    }

    @Test
    void 주소를_비워_보내면_지금_값이_그대로_남는다() {
        controller.update("dad", request(null));
        controller.update("dad", request("   "));

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        verify(endpointProbe, never()).requireReachable(anyString(), anyString());
    }

    @Test
    void 끝에_슬래시를_붙여_보내면_떼고_저장한다() {
        controller.update("dad", request("http://127.0.0.1:2/p/dad/"));

        assertThat(agent.apiBaseUrl()).isEqualTo("http://127.0.0.1:2/p/dad");
    }

    @Test
    void 확인이_실패하면_저장하지_않고_값이_그대로다() {
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "the new address answered 404"))
                .when(endpointProbe).requireReachable(anyString(), anyString());

        assertThatThrownBy(() -> controller.update("dad", request("http://127.0.0.1:2/p/dad")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("404");

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    void 확인은_그_에이전트의_profile_로_나간다() {
        controller.update("dad", request("http://127.0.0.1:2/p/dad"));

        verify(endpointProbe).requireReachable("http://127.0.0.1:2/p/dad", "dad");
    }

    @Test
    void 그룹_공개_전에_도구를_읽지_못하면_접근_범위를_바꾸지_않는다() {
        agent = Agent.of("dad", "Dad", "dad", CURRENT_URL,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 1L);
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "invalid toolset response"))
                .when(hermesToolsets).readEnabled(CURRENT_URL, "dad");

        assertThatThrownBy(() -> controller.update("dad", request(null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);

        assertThat(agent.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        verify(agents, never()).save(agent);
    }

    @Test
    void 그룹_공개와_주소_변경을_함께_보내도_새_주소의_도구를_검사한다() {
        agent = Agent.of("dad", "Dad", "dad", CURRENT_URL,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 1L);
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("dad")).thenAnswer(call -> Optional.of(agent));
        when(hermesToolsets.readEnabled("http://127.0.0.1:2/p/dad", "dad")).thenReturn(java.util.List.of("terminal"));

        assertThatThrownBy(() -> controller.update("dad", request("http://127.0.0.1:2/p/dad")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agent.apiBaseUrl()).isEqualTo(CURRENT_URL);
        assertThat(agent.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        verify(agents, never()).save(agent);
    }

    @Test
    void 그룹_에이전트를_새로_만들_때도_private_toolset을_거절한다() {
        when(agents.findByCode("group")).thenReturn(Optional.empty());
        when(hermesToolsets.readEnabled("http://127.0.0.1:2/p/group", "group-profile"))
                .thenReturn(java.util.List.of("terminal"));

        assertThatThrownBy(() -> controller.create(new CreateAgentRequest(
                        "group", "Group", "group-profile", "http://127.0.0.1:2/p/group",
                        CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    void provider_없이_자기만_보는_에이전트를_등록하면_주소만_확인하고_저장한다() {
        when(agents.findByCode("mom")).thenReturn(Optional.empty());
        AppUser owner = owner(7L);
        when(users.findByEmail("mom@example.com")).thenReturn(Optional.of(owner));

        AdminAgentView view = controller.create(privateRequest("mom", "http://127.0.0.1:2/p/mom/"));

        assertThat(view)
                .extracting(AdminAgentView::code, AdminAgentView::apiBaseUrl, AdminAgentView::visibility,
                        AdminAgentView::ownerUserId)
                .containsExactly("mom", "http://127.0.0.1:2/p/mom", "PRIVATE", 7L);
        verify(endpointProbe).requireReachable("http://127.0.0.1:2/p/mom/", "mom-profile");
        verify(agents).save(any(Agent.class));
        // 자기만 보는 에이전트는 도구를 검사하지 않는다. 등록이 Hermes 에 묻는 것은 주소 확인 하나뿐이다.
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    void 등록할_주소가_닿지_않으면_거절하고_저장하지_않는다() {
        when(agents.findByCode("mom")).thenReturn(Optional.empty());
        AppUser owner = owner(7L);
        when(users.findByEmail("mom@example.com")).thenReturn(Optional.of(owner));
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "the new address answered 404 for /v1/capabilities"))
                .when(endpointProbe).requireReachable("http://127.0.0.1:2/p/mom", "mom-profile");

        assertThatThrownBy(() -> controller.create(privateRequest("mom", "http://127.0.0.1:2/p/mom")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    void 그룹_에이전트를_끄는_요청은_도구를_읽지_않는다() {
        AdminAgentView view = controller.update("dad", new UpdateAgentRequest(false, AgentVisibility.GROUP, null, null));

        assertThat(view.enabled()).isFalse();
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    void 꺼진_그룹_에이전트를_켜기_전에는_private_toolset을_검사한다() {
        agent.changeAccess(false, AgentVisibility.GROUP, null);
        when(hermesToolsets.readEnabled(CURRENT_URL, "dad")).thenReturn(java.util.List.of("terminal"));

        assertThatThrownBy(() -> controller.update("dad", request(null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agent.enabled()).isFalse();
        verify(agents, never()).save(agent);
    }

    private static CreateAgentRequest privateRequest(String code, String apiBaseUrl) {
        return new CreateAgentRequest(code, "Mom", code + "-profile", apiBaseUrl,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE,
                code + "@example.com", null);
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
