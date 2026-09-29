package com.bifos.assistant.mcp.application;

import com.bifos.assistant.orchestration.application.SessionOwnerResolver;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * MCP 도구 호출의 요청자를 정한다(ADR-032).
 *
 * <p>profile 이 묶인 토큰은 서명 확인, origin 실행 찾기(ADR-037), 그 실행의 사용자 읽기를 차례로 한다.
 * 토큰이 어느 사용자로 발급됐었는지는 보지 않는다. profile 이 빈 옛 토큰은 인증을 통과한 경우(설정이 참일
 * 때)에만 여기 오고, {@code _fos_ctx} 를 보지 않고 그 토큰의 사용자를 쓴다.
 *
 * <p>어느 단계에서 실패하든 밖에는 같은 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 하나만 보인다. 서명이 틀린
 * 것과 남의 profile 이 도는 것을 밖에서 나누지 못하게 해, 다른 사용자가 지금 실행 중인지 훑어 알아내지 못하게
 * 한다. 이유는 로그에만 남기고 토큰 해시와 {@code sig} 는 적지 않는다.
 */
@Service
@RequiredArgsConstructor
public class McpCallerResolver {

    private static final Logger log = LoggerFactory.getLogger(McpCallerResolver.class);

    private final SessionOwnerResolver owners;
    private final AppUserRepository users;

    /**
     * @param principal 요청을 인증한 토큰
     * @param toolName 서버 쪽 도구 이름. 서명할 글에 들어간다
     * @param fosCtx 원래 도구 인자의 {@code _fos_ctx}. 없으면 null
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 요청자를 정하지 못했을 때
     */
    @Transactional(readOnly = true)
    public McpCaller resolve(McpPrincipal principal, String toolName, JsonNode fosCtx) {
        if (principal == null) throw reject(toolName, "인증 주체가 MCP 토큰이 아니다");
        if (!principal.bound()) {
            AppUser user = findUser(principal.legacyUserId())
                    .orElseThrow(() -> reject(toolName, "옛 토큰의 사용자가 없다"));
            return new McpCaller(current(user), null, null);
        }
        McpCallContext context = McpCallContext.verify(toolName, fosCtx, principal.tokenHash());
        AgentExecution origin = owners.resolve(principal.profileName(), context.rootSessionId(), context.sessionId());
        AppUser user = findUser(origin.userId())
                .orElseThrow(() -> reject(toolName, "origin 실행의 사용자가 없다"));
        return new McpCaller(current(user), origin, context);
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
