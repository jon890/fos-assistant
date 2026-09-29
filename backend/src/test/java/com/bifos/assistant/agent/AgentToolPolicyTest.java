package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 도구 등급과 비공개 제약을 한곳에서 판정하는지 본다. */
class AgentToolPolicyTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(2L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    @Test
    void 주인은_주인_등급을_켜고_기억_MCP는_항상_남는다() {
        List<String> result = AgentToolPolicy.requestedForWrite(
                OWNER, agent(AgentVisibility.PRIVATE), List.of("web"), List.of());

        assertThat(result).containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    void 주인은_관리자_등급을_새로_켤_수_없다() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("terminal"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 주인은_이미_켜진_관리자_등급도_끌_수_없다() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of(), List.of("terminal")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void 관리자는_관리자_등급을_켤_수_있다() {
        assertThat(AgentToolPolicy.requestedForWrite(
                ADMIN, agent(AgentVisibility.PRIVATE), List.of("terminal"), List.of()))
                .containsExactly("terminal", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    void memory와_모르는_이름은_거절한다() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("memory"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.PRIVATE), List.of("unknown"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 그룹_에이전트는_셸과_파일_계열을_켤_수_없다() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.GROUP), List.of("terminal"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
    }

    @Test
    void 지난_대화_검색은_관리자만_켜고_그룹_에이전트에는_둘_수_없다() {
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.PRIVATE), List.of("session_search"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        ADMIN, agent(AgentVisibility.GROUP), List.of("session_search"), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        assertThat(AgentToolPolicy.requestedForWrite(
                ADMIN, agent(AgentVisibility.PRIVATE), List.of("session_search"), List.of()))
                .containsExactly("session_search", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    @Test
    void 지난_대화_검색이_이미_켜진_그룹_에이전트는_관리자가_끌_때까지_주인이_다른_도구를_바꾸지_못한다() {
        // 등급을 옮기기 전에 켜 둔 에이전트다. 주인은 그 도구를 끄지 못하고, 남긴 채 저장하면 그룹 제약에 걸린다.
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.GROUP), List.of("session_search", "web"), List.of("session_search")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);
        assertThatThrownBy(() -> AgentToolPolicy.requestedForWrite(
                        OWNER, agent(AgentVisibility.GROUP), List.of("web"), List.of("session_search")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(AgentToolPolicy.requestedForWrite(
                ADMIN, agent(AgentVisibility.GROUP), List.of("web"), List.of("session_search")))
                .containsExactly("web", AgentToolPolicy.CONTROL_PLANE_MCP);
    }

    private static Agent agent(AgentVisibility visibility) {
        return Agent.of("tool-agent", "도구", "tool-profile", "http://example.test/p/tool-profile",
                "provider", "model", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, visibility, 1L);
    }
}
