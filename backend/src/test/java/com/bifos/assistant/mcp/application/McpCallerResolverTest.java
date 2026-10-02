package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 커넥터 에이전트의 실행에서 온 Control Plane 도구 호출을 요청자 판정에서 거절하는 것을 고정한다(ADR-045).
 *
 * <p>서명이 맞고 origin 실행도 찾았는데 거절하는 경우라, 서명이 틀린 호출과 같은 코드로 답하는지를 함께 본다.
 */
class McpCallerResolverTest {

    private static final String TOKEN = "test-mcp-token-0001";
    private static final String PROFILE = "connector-profile";
    private static final String ROOT_SESSION_ID = "fos-00000000-0000-4000-8000-000000000001";
    private static final String TOOL = "memory_read";
    private static final Long AGENT_ID = 7L;
    private static final Long USER_ID = 11L;

    private final SessionOwnerResolver owners = mock(SessionOwnerResolver.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentExecution origin = mock(AgentExecution.class);
    private final McpPrincipal principal = new McpPrincipal(1L, PROFILE, AgentTokenService.hash(TOKEN));
    private final McpCallerResolver resolver = new McpCallerResolver(owners, users, agents);

    @BeforeEach
    void setUp() {
        AppUser user = mock(AppUser.class);
        when(user.id()).thenReturn(USER_ID);
        when(user.email()).thenReturn("owner@example.com");
        when(user.displayName()).thenReturn("가");
        when(user.groupId()).thenReturn(1L);
        when(user.role()).thenReturn(UserRole.MEMBER);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(origin.userId()).thenReturn(USER_ID);
        when(origin.agentId()).thenReturn(AGENT_ID);
        when(owners.resolve(PROFILE, ROOT_SESSION_ID, ROOT_SESSION_ID)).thenReturn(origin);
    }

    @Test
    @DisplayName("origin 실행의 에이전트가 커넥터 에이전트이면 MCP_CALL_CONTEXT_INVALID 로 거절한다")
    void rejectsCallFromConnectorAgentRun() {
        Agent connector = agent();
        connector.markConnectorManaged();
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(connector));

        assertThatThrownBy(() -> resolver.resolve(principal, TOOL, signedContext()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code())
                                .as("커넥터 에이전트의 호출을 거절한 코드")
                                .isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }

    @Test
    @DisplayName("origin 실행의 에이전트가 일반 에이전트이면 요청자는 origin 실행의 사용자다")
    void resolvesOriginUserForOrdinaryAgentRun() {
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(agent()));

        McpCaller caller = resolver.resolve(principal, TOOL, signedContext());

        assertThat(caller.user().id()).as("요청자의 사용자 번호").isEqualTo(USER_ID);
        assertThat(caller.originExecution()).isSameAs(origin);
    }

    @Test
    @DisplayName("origin 실행의 에이전트 행이 없으면 거절하지 않는다")
    void doesNotRejectWhenAgentRowIsMissing() {
        when(agents.findById(AGENT_ID)).thenReturn(Optional.empty());

        McpCaller caller = resolver.resolve(principal, TOOL, signedContext());

        assertThat(caller.user().id()).as("요청자의 사용자 번호").isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("origin 실행에 에이전트 번호가 없으면 거절하지 않는다")
    void doesNotRejectWhenOriginHasNoAgentId() {
        when(origin.agentId()).thenReturn(null);

        McpCaller caller = resolver.resolve(principal, TOOL, signedContext());

        assertThat(caller.user().id()).as("요청자의 사용자 번호").isEqualTo(USER_ID);
    }

    private static JsonNode signedContext() {
        return McpCallSigner.context(TOKEN, TOOL, ROOT_SESSION_ID);
    }

    private static Agent agent() {
        return Agent.of(
                "calendar",
                "일정",
                PROFILE,
                "http://agent-runtime.test/p/calendar",
                CostMode.SUBSCRIPTION,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                USER_ID, Instant.now());
    }
}
