package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.application.AgentToolView;
import com.bifos.assistant.agent.application.AgentToolsetsView;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.application.ToolsetVisibilityService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Hermes 설정 저장 뒤의 재조회와 대시보드 실패 처리를 본다. */
class AgentToolServiceTest {

    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final ProfileSkillFiles skillFiles = mock(ProfileSkillFiles.class);
    private final AgentConnectorBindings connectorBindings = mock(AgentConnectorBindings.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentToolService service = new AgentToolService(
            toolsets,
            skillFiles,
            mock(AgentService.class),
            agents,
            connectorBindings,
            mock(ToolsetVisibilityService.class));
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final Agent agent = Agent.of(
            "tools",
            "도구",
            "tools-profile",
            "http://listener.test/p/tools-profile",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            1L,
            Instant.now());

    @BeforeEach
    void setUp() {
        when(toolsets.readCatalog()).thenReturn(List.of(new ToolsetCatalogEntry("web", "Web", "검색")));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of(), List.of());
    }

    @Test
    @DisplayName("기존 개인 profile에 원본 조회를 자동 제공하고 도구 설정을 보존한다")
    void originalInspectionIsAddedAutomaticallyToExistingPrivateProfile() {
        when(agents.findByCodeForUpdate(agent.code())).thenReturn(Optional.of(agent));
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("mcp-demo"));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("web"), List.of("web", "fos-attachments"));
        service.ensureAttachmentInspection(agent);
        Mockito.verify(toolsets)
                .writeApiServer(
                        agent.hermesProfile(),
                        List.of("web", "fos-assistant", "fos-attachments", "mcp-demo"),
                        agent.sandboxOwner());
    }

    @Test
    @DisplayName("커넥터와 공유 profile에는 원본 조회를 자동 제공하지 않는다")
    void connectorAndSharedProfilesAreNotGivenOriginalInspection() {
        Agent isolated = mock(Agent.class);
        when(isolated.connectorManaged()).thenReturn(true);
        service.ensureAttachmentInspection(isolated);
        Agent shared = mock(Agent.class);
        when(shared.acceptsAttachments()).thenReturn(false);
        service.ensureAttachmentInspection(shared);
        Mockito.verifyNoInteractions(toolsets);
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
        AgentToolService isolatedService = new AgentToolService(
                isolatedToolsets,
                mock(ProfileSkillFiles.class),
                mock(AgentService.class),
                mock(AgentRepository.class),
                mock(AgentConnectorBindings.class),
                mock(ToolsetVisibilityService.class));
        Agent groupAgent = Agent.of(
                "group-tools",
                "그룹 도구",
                "group-tools-profile",
                "http://listener.test/p/group-tools-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now());
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
        AgentToolService isolatedService = new AgentToolService(
                isolatedToolsets,
                mock(ProfileSkillFiles.class),
                mock(AgentService.class),
                mock(AgentRepository.class),
                mock(AgentConnectorBindings.class),
                mock(ToolsetVisibilityService.class));
        Agent groupAgent = Agent.of(
                "group-read",
                "그룹 도구",
                "group-read-profile",
                "http://listener.test/p/group-read-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now());
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

        AgentToolsetsView result = service.read(owner, agent);

        Assertions.assertThat(result.toolsets()).extracting(AgentToolView::name).containsExactly("web");
        Assertions.assertThat(result.unclassifiedEnabled()).containsExactly("connections");
    }

    @Test
    @DisplayName("붙은 커넥터 서버는 미분류 목록에 없고 저장할 때 목록 끝에 함께 보낸다")
    void leavesConnectorServersOutOfUnclassifiedAndSendsThemOnWrite() {
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("demo"));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("web", "demo", "connections"), List.of("web", "demo"), List.of("web", "demo"));

        AgentToolsetsView read = service.read(owner, agent);
        AgentToolsetsView written = service.write(owner, agent, List.of("web", "demo"));

        assertThat(read.unclassifiedEnabled()).containsExactly("connections");
        assertThat(written.unclassifiedEnabled()).isEmpty();
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant", "demo"), "u1");
    }

    @Test
    @DisplayName("저장 뒤 자동으로 켜진 미분류 toolset은 성공 응답에 남긴다")
    void keepsAutoEnabledUnclassifiedToolsetInSuccessResponse() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of(), List.of("web", "connections"));

        AgentToolsetsView result = service.write(owner, agent, List.of("web"));

        assertThat(result.unclassifiedEnabled()).containsExactly("connections");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant"), "u1");
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
    @DisplayName("실행 공간이 준비되지 않아 저장이 거절되면 AGENT_SANDBOX_UNAVAILABLE 을 그대로 돌린다")
    void passesSandboxUnavailableThroughWhenWriteIsRejected() {
        doThrow(new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "the isolated shell workspace is not configured"))
                .when(toolsets)
                .writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant"), "u1");

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_SANDBOX_UNAVAILABLE));
    }

    @Test
    @DisplayName("올린 스킬이 있으면 skills 를 끄는 저장을 거절하고 Hermes 를 부르지 않는다")
    void rejectsDisablingSkillsWhenUploadedSkillsExistAndSkipsHermes() {
        when(skillFiles.hasUploaded(agent.hermesProfile())).thenReturn(true);

        assertThatThrownBy(() -> service.write(owner, agent, List.of("web")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(toolsets, Mockito.never())
                .writeApiServer(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("올린 스킬이 있어도 skills 를 둔 저장은 그대로 쓴다")
    void keepsSaveThatLeavesSkillsOnEvenWithUploadedSkills() {
        when(skillFiles.hasUploaded(agent.hermesProfile())).thenReturn(true);
        when(toolsets.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "Web", "검색"),
                        new ToolsetCatalogEntry("skills", "Skills", "스킬")));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("skills"), List.of("web", "skills"));

        AgentToolsetsView result = service.write(owner, agent, List.of("web", "skills"));

        assertThat(result.toolsets()).extracting(AgentToolView::name).containsExactly("web", "skills");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "skills", "fos-assistant"), "u1");
    }

    @Test
    @DisplayName("비밀 요청 스킬이 올라간 에이전트에 terminal 을 켜면 AGENT_SKILL_REQUESTS_SECRETS 로 거절하고 Hermes 에 쓰지 않는다")
    void rejectsEnablingTerminalWhenUploadedSkillRequestsSecrets() {
        when(skillFiles.uploadedRequestingSecrets(agent.hermesProfile())).thenReturn(List.of("legacy-env"));
        when(toolsets.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "Web", "검색"),
                        new ToolsetCatalogEntry("terminal", "Terminal", "셸")));

        // terminal 은 관리자 등급이라 관리자가 켠다.
        CurrentUser admin = new CurrentUser(9L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

        assertThatThrownBy(() -> service.write(admin, agent, List.of("web", "terminal")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_SKILL_REQUESTS_SECRETS);
                    assertThat(ex.getMessage()).endsWith(": legacy-env");
                });

        verify(toolsets, Mockito.never())
                .writeApiServer(ArgumentMatchers.anyString(), ArgumentMatchers.anyList(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("비밀 요청 스킬이 올라가 있어도 셸 도구가 없는 저장은 그대로 쓴다")
    void keepsSaveWithoutSandboxToolsetEvenWhenUploadedSkillRequestsSecrets() {
        when(skillFiles.uploadedRequestingSecrets(agent.hermesProfile())).thenReturn(List.of("legacy-env"));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of(), List.of("web"));

        AgentToolsetsView result = service.write(owner, agent, List.of("web"));

        assertThat(result.toolsets()).extracting(AgentToolView::name).containsExactly("web");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "fos-assistant"), "u1");
    }
}
