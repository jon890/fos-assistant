package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 도구 등급과 비공개 제약을 한곳에서 판정하는지 본다. */
class AgentToolPolicyTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(2L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    @Test
    @DisplayName("주인은 주인 등급을 켜고 기억 MCP는 항상 남는다")
    void ownerEnablesOwnerTierAndMemoryMcpAlwaysStays() {
        List<String> result = AgentToolPolicy.requestedForWrite(
                OWNER, agent(AgentVisibility.PRIVATE), List.of("web"), List.of(), Set.of());

        assertThat(result).containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    @DisplayName("내부 원본 조회 도구는 사용자 설정과 스킬 발행 뒤에도 유지한다")
    void internalOriginalInspectionSurvivesUserToolAndSkillPublishUpdates() {
        List<String> result = AgentToolPolicy.requestedForWrite(
                OWNER,
                agent(AgentVisibility.PRIVATE),
                List.of("web", "skills"),
                List.of("fos-assistant", "fos-attachments"),
                Set.of());
        assertThat(result).contains("fos-attachments");
        assertThat(AgentToolPolicy.isKnown("fos-attachments")).isFalse();
        assertThat(AgentToolPolicy.requestedForWrite(
                        OWNER,
                        agent(AgentVisibility.GROUP),
                        List.of("web"),
                        List.of("fos-assistant", "fos-attachments"),
                        Set.of()))
                .doesNotContain("fos-attachments");
    }

    @Test
    @DisplayName("주인은 관리자 등급을 새로 켤 수 없다")
    void ownerCannotNewlyEnableAdminTier() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("terminal"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("주인은 이미 켜진 관리자 등급도 끌 수 없다")
    void ownerCannotDisableAlreadyEnabledAdminTier() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of(), List.of("terminal"), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자는 관리자 등급을 켤 수 있다")
    void adminCanEnableAdminTier() {
        assertThat(AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("terminal"), List.of(), Set.of()))
                .containsExactly("terminal", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    @DisplayName("memory와 모르는 이름은 거절한다")
    void rejectsMemoryAndUnknownNames() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("memory"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("unknown"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("그룹 에이전트는 셸과 파일 계열을 켤 수 없다")
    void groupAgentCannotEnableShellAndFileFamilies() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.GROUP), List.of("terminal"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
    }

    @Test
    @DisplayName("사진 보기만 켜도 비공개 실행 공간을 요구한다")
    void visionRequiresPrivateSandboxEvenWithoutShellTools() {
        assertThat(AgentToolPolicy.hasSandboxToolset(List.of("vision"))).isTrue();
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.GROUP), List.of("vision"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        assertThat(AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("vision"), List.of(), Set.of()))
                .containsExactly("vision", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    @DisplayName("이미지 만들기도 원본 사진을 읽으므로 비공개 실행 공간을 요구한다")
    void imageGenerationRequiresPrivateSandbox() {
        for (String toolset : List.of("image_gen", "video_gen")) {
            assertThat(AgentToolPolicy.hasSandboxToolset(List.of(toolset))).isTrue();
            assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                            ADMIN, agent(AgentVisibility.GROUP), List.of(toolset), List.of(), Set.of()))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        }
    }

    @Test
    @DisplayName("지난 대화 검색은 관리자만 켜고 그룹 에이전트에는 둘 수 없다")
    void pastConversationSearchIsAdminOnlyAndNotAllowedForGroupAgent() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("session_search"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.GROUP), List.of("session_search"), List.of(), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        assertThat(AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("session_search"), List.of(), Set.of()))
                .containsExactly("session_search", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    @DisplayName("지난 대화 검색이 이미 켜진 그룹 에이전트는 관리자가 끌 때까지 주인이 다른 도구를 바꾸지 못한다")
    void ownerCannotChangeOtherToolsUntilAdminDisablesPastSearch() {
        // 등급을 옮기기 전에 켜 둔 에이전트다. 주인은 그 도구를 끄지 못하고, 남긴 채 저장하면 그룹 제약에 걸린다.
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER,
                        agent(AgentVisibility.GROUP),
                        List.of("session_search", "web"),
                        List.of("session_search"),
                        Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.GROUP), List.of("web"), List.of("session_search"), Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.GROUP), List.of("web"), List.of("session_search"), Set.of()))
                .containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    @DisplayName("붙은 커넥터 서버 이름은 목록 끝에 더하고 요청에 들어와도 거절하지 않는다")
    void appendsConnectorServersAndAcceptsThemInRequest() {
        Set<String> servers = Set.of("demo");

        assertThat(AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("web"), List.of("web"), servers))
                .containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP, "demo");
        assertThat(AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("demo", "web"), List.of("web", "demo"), servers))
                .containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP, "demo");
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("other"), List.of(), servers))
                .as("붙지 않은 서버 이름은 지금처럼 모르는 이름이다")
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static Agent agent(AgentVisibility visibility) {
        return Agent.of(
                "tool-agent",
                "도구",
                "tool-profile",
                "http://example.test/p/tool-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                1L,
                Instant.now());
    }
}
