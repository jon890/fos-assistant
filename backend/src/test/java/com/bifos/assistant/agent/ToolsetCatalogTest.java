package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.ToolsetCatalogService;
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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolsetCatalogTest {
    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final ToolsetVisibilityService visibility = mock(ToolsetVisibilityService.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ToolsetCatalogService catalog = new ToolsetCatalogService(visibility, toolsets, agents);
    private final CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    @Test
    @DisplayName("숨김 도구의 켜진 에이전트를 세며 꺼진 에이전트와 Hermes 목록에서 사라진 숨김도 남긴다")
    void includesDisabledAgentsAndHiddenNamesMissingFromHermes() {
        Agent agent = Agent.of("count-tools", "꺼진 비서", "count-tools", "http://runtime.test/p/count-tools",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 1L, Instant.now());
        agent.changeAccess(false, AgentVisibility.PRIVATE, 1L);
        when(visibility.hiddenFor(1L)).thenReturn(Set.of("spotify", "discord"));
        when(toolsets.readCatalog()).thenReturn(List.of(new ToolsetCatalogEntry("spotify", "Spotify", "음악"),
                new ToolsetCatalogEntry("memory", "Memory", "기억")));
        when(agents.findByDeletedAtIsNullAndConnectorManagedFalseOrderByCodeAsc()).thenReturn(List.of(agent));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(List.of("spotify"));
        assertThat(catalog.read(admin)).satisfiesExactly(spotify -> {
            assertThat(spotify.hidden()).isTrue();
            assertThat(spotify.enabledAgents()).hasSize(1);
            assertThat(spotify.enabledAgents().getFirst().code()).isEqualTo(agent.code());
        }, discord -> {
            assertThat(discord.name()).isEqualTo("discord");
            assertThat(discord.hidden()).isTrue();
            assertThat(discord.enabledAgents()).isEmpty();
        });
    }

    @Test
    @DisplayName("활성 도구를 읽지 못하면 0개로 돌리지 않고 실패한다")
    void propagatesProfileReadFailuresRatherThanClaimingZeroEnabledAgents() {
        Agent agent = Agent.of("count-failure", "실패 비서", "count-failure", "http://runtime.test/p/count-failure",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, null, Instant.now());
        when(agents.findByDeletedAtIsNullAndConnectorManagedFalseOrderByCodeAsc()).thenReturn(List.of(agent));
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "unavailable"));
        assertThatThrownBy(() -> catalog.read(admin)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }
}
