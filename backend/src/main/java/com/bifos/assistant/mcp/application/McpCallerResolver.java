package com.bifos.assistant.mcp.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * MCP 도구 호출의 요청자를 정한다(ADR-032).
 *
 * <p>서명 확인, origin 실행 찾기(ADR-037), 그 실행의 사용자 읽기를 차례로 한다. 토큰은 profile 만 증명하므로
 * 토큰에서 사용자를 읽지 않는다.
 *
 * <p>origin 실행의 에이전트가 커넥터 에이전트이면 도구 이름과 관계없이 거절한다(ADR-045). 외부 서비스의 글이
 * 모델을 속여도 Memory, 결과물 폴더, 다른 에이전트에 닿지 못하게 하기 위해서다. 토큰 인증은 그대로 통과시키고
 * 도구 호출의 요청자 판정에서만 막는다.
 *
 * <p>어느 단계에서 실패하든 밖에는 같은 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 하나만 보인다. 서명이 틀린
 * 것과 남의 profile 이 도는 것을 밖에서 나누지 못하게 해, 다른 사용자가 지금 실행 중인지 훑어 알아내지 못하게
 * 한다. 이유는 로그에만 남기고 토큰 해시와 {@code sig} 는 적지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class McpCallerResolver {

    private final SessionOwnerResolver owners;
    private final AppUserRepository users;
    private final AgentRepository agents;

    /**
     * @param principal 요청을 인증한 토큰
     * @param toolName 서버 쪽 도구 이름. 서명할 글에 들어간다
     * @param fosCtx 원래 도구 인자의 {@code _fos_ctx}. 없으면 null
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 요청자를 정하지 못했을 때
     */
    @Transactional(readOnly = true)
    public McpCaller resolve(McpPrincipal principal, String toolName, JsonNode fosCtx) {
        if (principal == null) {
            throw reject(toolName, "인증 주체가 MCP 토큰이 아니다");
        }
        McpCallContext context = McpCallContext.verify(toolName, fosCtx, principal.tokenHash());
        AgentExecution origin = owners.resolve(principal.profileName(), context.rootSessionId(), context.sessionId());
        if (isConnectorAgent(origin.agentId())) {
            throw reject(toolName, "커넥터 에이전트는 Control Plane 도구를 쓰지 못한다");
        }
        AppUser user = findUser(origin.userId()).orElseThrow(() -> reject(toolName, "origin 실행의 사용자가 없다"));
        return new McpCaller(current(user), origin, context);
    }

    /** 에이전트 번호가 없거나 그 행이 없으면 커넥터 에이전트로 보지 않는다. */
    private boolean isConnectorAgent(Long agentId) {
        return agentId != null
                && agents.findById(agentId).map(Agent::connectorManaged).orElse(false);
    }

    private Optional<AppUser> findUser(Long userId) {
        return userId == null ? Optional.empty() : users.findById(userId);
    }

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 서명 실패와 같다. */
    private static ApiException reject(String toolName, String reason) {
        log.warn("MCP 호출의 요청자를 정하지 못했다 tool={} reason={}", toolName, reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
