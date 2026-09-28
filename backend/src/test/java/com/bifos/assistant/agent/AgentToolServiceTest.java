package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hermes 설정 저장 뒤의 재조회와 대시보드 실패 처리를 본다. */
class AgentToolServiceTest {

    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final AgentToolService service = new AgentToolService(toolsets);
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final Agent agent = Agent.of("tools", "도구", "tools-profile", "http://listener.test/p/tools-profile",
            "provider", "model", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 1L);

    @BeforeEach
    void 준비한다() {
        when(toolsets.readCatalog()).thenReturn(List.of(new ToolsetCatalogEntry("web", "Web", "검색")));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of());
    }

    @Test
    void 저장_뒤_다른_목록이_오면_적용되지_않은_오류를_돌린다() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of());

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_NOT_APPLIED);
    }

    @Test
    void 대시보드를_읽지_못하면_HERMES_UNAVAILABLE을_그대로_돌린다() {
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not reach Hermes"))
                .when(toolsets).readCatalog();

        assertThatThrownBy(() -> service.read(owner, agent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    void 그룹_에이전트를_읽을_수_있는_다른_사용자도_주인_등급을_바꾸지_못한다() {
        HermesToolsetClient isolatedToolsets = mock(HermesToolsetClient.class);
        AgentToolService isolatedService = new AgentToolService(isolatedToolsets);
        Agent groupAgent = Agent.of("group-tools", "그룹 도구", "group-tools-profile",
                "http://listener.test/p/group-tools-profile", "provider", "model",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, null);
        CurrentUser reader = new CurrentUser(2L, "reader@example.com", "읽는 사람", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> isolatedService.write(reader, groupAgent, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(isolatedToolsets);
    }

    @Test
    void 그룹_에이전트를_읽을_수_있는_다른_사용자도_도구_목록을_읽지_못한다() {
        HermesToolsetClient isolatedToolsets = mock(HermesToolsetClient.class);
        AgentToolService isolatedService = new AgentToolService(isolatedToolsets);
        Agent groupAgent = Agent.of("group-read", "그룹 도구", "group-read-profile",
                "http://listener.test/p/group-read-profile", "provider", "model",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, null);
        CurrentUser reader = new CurrentUser(2L, "reader@example.com", "읽는 사람", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> isolatedService.read(reader, groupAgent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(isolatedToolsets);
    }

    @Test
    void policy에_없는_켜진_toolset은_별도_목록으로_돌린다() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("web", "connections", "memory"));

        AgentToolService.ToolsetsView result = service.read(owner, agent);

        org.assertj.core.api.Assertions.assertThat(result.toolsets()).extracting(AgentToolService.ToolView::name)
                .containsExactly("web");
        org.assertj.core.api.Assertions.assertThat(result.unclassifiedEnabled()).containsExactly("connections");
    }

    @Test
    void 저장_뒤_자동으로_켜진_미분류_toolset은_성공_응답에_남긴다() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of("web", "connections"));

        AgentToolService.ToolsetsView result = service.write(owner, agent, List.of("web"));

        assertThat(result.unclassifiedEnabled()).containsExactly("connections");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant-memory"));
    }

    @Test
    void 저장_뒤_예상하지_않은_policy_known_toolset은_거절한다() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of("web", "terminal"));

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_NOT_APPLIED);
    }
}
