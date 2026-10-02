package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Hermes 설정 저장 뒤의 재조회와 대시보드 실패 처리를 본다. */
class AgentToolServiceTest {

    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final SkillStore skillStore = mock(SkillStore.class);
    private final AgentToolService service = new AgentToolService(toolsets, skillStore);
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final Agent agent = Agent.of(
            "tools",
            "도구",
            "tools-profile",
            "http://listener.test/p/tools-profile",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            1L);

    @BeforeEach
    void setUp() {
        when(toolsets.readCatalog()).thenReturn(List.of(new ToolsetCatalogEntry("web", "Web", "검색")));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of(), List.of());
    }

    @Test
    @DisplayName("저장 뒤 다른 목록이 오면 적용되지 않은 오류를 돌린다")
    void returnsNotAppliedErrorWhenDifferentListComesBackAfterSave() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of(), List.of());

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_NOT_APPLIED);
    }

    @Test
    @DisplayName("대시보드를 읽지 못하면 HERMES UNAVAILABLE을 그대로 돌린다")
    void passesHermesUnavailableThroughWhenDashboardUnreadable() {
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not reach Hermes"))
                .when(toolsets)
                .readCatalog();

        assertThatThrownBy(() -> service.read(owner, agent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("그룹 에이전트를 읽을 수 있는 다른 사용자도 주인 등급을 바꾸지 못한다")
    void otherUserReadingGroupAgentCannotChangeOwnerTier() {
        HermesToolsetClient isolatedToolsets = mock(HermesToolsetClient.class);
        AgentToolService isolatedService = new AgentToolService(isolatedToolsets, mock(SkillStore.class));
        Agent groupAgent = Agent.of(
                "group-tools",
                "그룹 도구",
                "group-tools-profile",
                "http://listener.test/p/group-tools-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null);
        CurrentUser reader = new CurrentUser(2L, "reader@example.com", "읽는 사람", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> isolatedService.write(reader, groupAgent, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(isolatedToolsets);
    }

    @Test
    @DisplayName("그룹 에이전트를 읽을 수 있는 다른 사용자도 도구 목록을 읽지 못한다")
    void otherUserReadingGroupAgentCannotReadToolList() {
        HermesToolsetClient isolatedToolsets = mock(HermesToolsetClient.class);
        AgentToolService isolatedService = new AgentToolService(isolatedToolsets, mock(SkillStore.class));
        Agent groupAgent = Agent.of(
                "group-read",
                "그룹 도구",
                "group-read-profile",
                "http://listener.test/p/group-read-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null);
        CurrentUser reader = new CurrentUser(2L, "reader@example.com", "읽는 사람", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> isolatedService.read(reader, groupAgent))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(isolatedToolsets);
    }

    @Test
    @DisplayName("policy에 없는 켜진 toolset은 별도 목록으로 돌린다")
    void returnsEnabledToolsetsMissingFromPolicyAsSeparateList() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("web", "connections", "memory"));

        AgentToolService.ToolsetsView result = service.read(owner, agent);

        Assertions.assertThat(result.toolsets())
                .extracting(AgentToolService.ToolView::name)
                .containsExactly("web");
        Assertions.assertThat(result.unclassifiedEnabled()).containsExactly("connections");
    }

    @Test
    @DisplayName("저장 뒤 자동으로 켜진 미분류 toolset은 성공 응답에 남긴다")
    void keepsAutoEnabledUnclassifiedToolsetInSuccessResponse() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of("web", "connections"));

        AgentToolService.ToolsetsView result = service.write(owner, agent, List.of("web"));

        assertThat(result.unclassifiedEnabled()).containsExactly("connections");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant"));
    }

    @Test
    @DisplayName("저장 뒤 예상하지 않은 policy known toolset은 거절한다")
    void rejectsUnexpectedPolicyKnownToolsetAfterSave() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of("web", "terminal"));

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_NOT_APPLIED);
    }

    @Test
    @DisplayName("올린 스킬이 있으면 skills 를 끄는 저장을 거절하고 Hermes 를 부르지 않는다")
    void rejectsDisablingSkillsWhenUploadedSkillsExistAndSkipsHermes() {
        when(skillStore.hasUploadedSkills(agent.hermesProfile())).thenReturn(true);

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(toolsets, Mockito.never())
                .writeApiServer(ArgumentMatchers.anyString(), ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("올린 스킬이 있어도 skills 를 둔 저장은 그대로 쓴다")
    void keepsSaveThatLeavesSkillsOnEvenWithUploadedSkills() {
        when(skillStore.hasUploadedSkills(agent.hermesProfile())).thenReturn(true);
        when(toolsets.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "Web", "검색"),
                        new ToolsetCatalogEntry("skills", "Skills", "스킬")));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("skills"), List.of("web", "skills"));

        AgentToolService.ToolsetsView result = service.write(owner, agent, List.of("web", "skills"));

        assertThat(result.toolsets())
                .extracting(AgentToolService.ToolView::name)
                .containsExactly("web", "skills");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "skills", "fos-assistant"));
    }
}
