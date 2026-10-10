package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.application.model.AgentToolView;
import com.bifos.assistant.agent.application.toolset.AgentToolService;
import com.bifos.assistant.agent.application.toolset.ToolsetVisibilityService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HiddenAgentToolsTest {
    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final ToolsetVisibilityService visibility = mock(ToolsetVisibilityService.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ProfileSkillFiles skills = mock(ProfileSkillFiles.class);
    private final AgentToolService service = new AgentToolService(
            toolsets, skills, mock(AgentService.class), agents, mock(AgentConnectorBindings.class), visibility);
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final CurrentUser admin = new CurrentUser(2L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);
    private final Agent agent = Agent.of(
            "hidden-tools",
            "숨김 시험",
            "hidden-tools",
            "http://runtime.test/p/hidden-tools",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            1L,
            Instant.now());

    @BeforeEach
    void setUp() {
        when(toolsets.readCatalog())
                .thenReturn(List.of(
                        new ToolsetCatalogEntry("web", "Web", "웹"),
                        new ToolsetCatalogEntry("spotify", "Spotify", "음악"),
                        new ToolsetCatalogEntry("skills", "Skills", "스킬")));
        when(visibility.hiddenFor(1L)).thenReturn(Set.of("spotify"));
        when(agents.findByCode(agent.code())).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate(agent.code())).thenReturn(Optional.of(agent));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("spotify"));
    }

    @Test
    @DisplayName("관리자도 일반 경로에서는 숨긴 도구를 받지 않고 관리자 경로에서는 숨김 표시를 받는다")
    void filtersGeneralViewsAndMarksHiddenToolsInAdminViews() {
        assertThat(service.read(owner, agent).toolsets())
                .extracting(AgentToolView::name)
                .doesNotContain("spotify");
        assertThat(service.read(admin, agent).toolsets())
                .extracting(AgentToolView::name)
                .doesNotContain("spotify");
        assertThat(service.readAsAdmin(admin, agent.code()).toolsets())
                .filteredOn(AgentToolView::hidden)
                .extracting(AgentToolView::name)
                .containsExactly("spotify");
    }

    @Test
    @DisplayName("일반 화면이 숨긴 도구를 빼고 저장해도 기존 관리자 도구의 활성 상태는 보존한다")
    void preservesAlreadyEnabledHiddenAdminToolsOnOwnerSave() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("spotify"), List.of("web", "spotify"));
        assertThat(service.write(owner, agent, List.of("web")).toolsets())
                .extracting(AgentToolView::name)
                .doesNotContain("spotify");
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "spotify", "fos-assistant"), "u1");
    }

    @Test
    @DisplayName("숨긴 도구를 일반 PUT에 넣으면 이미 켜져 있어도 거절한다")
    void rejectsExplicitHiddenToolInGeneralRequest() {
        assertThatThrownBy(() -> service.write(owner, agent, List.of("spotify")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("관리자는 숨긴 도구를 관리자 경로에서 끌 수 있다")
    void letsAdminDisableHiddenTools() {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("spotify"), List.of());
        assertThat(service.writeAsAdmin(admin, agent.code(), List.of()).toolsets())
                .filteredOn(AgentToolView::hidden)
                .allMatch(tool -> !tool.enabled());
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("fos-assistant"), "u1");
    }

    @Test
    @DisplayName("셸과 스킬을 숨겨도 연결 안내에 쓰는 실제 활성 여부는 남긴다")
    void keepsActualEnabledFlagsWhenShellAndSkillsAreHidden() {
        when(visibility.hiddenFor(1L)).thenReturn(Set.of("terminal", "skills", "spotify"));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("terminal", "skills"));
        var view = service.read(owner, agent);
        assertThat(view.toolsets()).extracting(AgentToolView::name).doesNotContain("terminal", "skills");
        assertThat(view.shellOrFileEnabled()).isTrue();
        assertThat(view.skillsEnabled()).isTrue();
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of());
        var disabled = service.read(owner, agent);
        assertThat(disabled.shellOrFileEnabled()).isFalse();
        assertThat(disabled.skillsEnabled()).isFalse();
    }

    @Test
    @DisplayName("올린 스킬이 있는 숨김 skills는 보존하며 중복 요청 검증도 유지한다")
    void preservesHiddenSkillsAndStillRejectsDuplicateRequests() {
        when(visibility.hiddenFor(1L)).thenReturn(Set.of("skills"));
        when(skills.hasUploaded(agent.hermesProfile())).thenReturn(true);
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of("skills"), List.of("web", "skills"));
        service.write(owner, agent, List.of("web"));
        verify(toolsets).writeApiServer(agent.hermesProfile(), List.of("web", "skills", "fos-assistant"), "u1");
        assertThatThrownBy(() -> service.write(owner, agent, List.of("web", "web")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
