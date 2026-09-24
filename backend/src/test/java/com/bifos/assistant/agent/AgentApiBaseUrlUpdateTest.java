package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentModelSync;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 관리 화면이 에이전트의 Hermes 주소를 고치는 길을 검사한다. */
class AgentApiBaseUrlUpdateTest {

    private static final String CURRENT_URL = "http://127.0.0.1:1/p/dad";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final HermesModelClient hermesModels = mock(HermesModelClient.class);
    private final AgentModelSync modelSync = mock(AgentModelSync.class);
    private final AgentModelSelector models = mock(AgentModelSelector.class);
    private final AgentEndpointProbe endpointProbe = mock(AgentEndpointProbe.class);
    private final FlowRegistry flows = mock(FlowRegistry.class);

    private final AgentAdminController controller = new AgentAdminController(
            agents, users, currentUser, hermesModels, modelSync, models, endpointProbe, flows);

    private Agent agent;

    @BeforeEach
    void seed() {
        agent = Agent.of("dad", "Dad", "dad", CURRENT_URL, "openai-codex", "example-model",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.FAMILY, null);
        when(agents.findByCode("dad")).thenReturn(Optional.of(agent));
        when(agents.save(any(Agent.class))).thenAnswer(call -> call.getArgument(0));
        when(models.optionsOf(any(Agent.class))).thenReturn(java.util.List.of());
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

    private static UpdateAgentRequest request(String apiBaseUrl) {
        return new UpdateAgentRequest(true, AgentVisibility.FAMILY, null, apiBaseUrl);
    }
}
