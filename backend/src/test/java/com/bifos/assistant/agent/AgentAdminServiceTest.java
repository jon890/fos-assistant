package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentAdminService;
import com.bifos.assistant.agent.application.AgentCreateCommand;
import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리자가 에이전트를 등록하는 유스케이스의 결과와 검사 순서를 본다. */
class AgentAdminServiceTest {

    private static final String API_BASE_URL = "http://127.0.0.1:2/p/group";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    private final AgentEndpointProbe endpointProbe = mock(AgentEndpointProbe.class);
    private final FlowRegistry flows = mock(FlowRegistry.class);

    private final AgentAdminService service = new AgentAdminService(agents, users, lifecycle, endpointProbe, flows);

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
