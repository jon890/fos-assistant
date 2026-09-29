package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 서명을 확인한 뿌리 session 과 토큰의 사용자로, 지금 도는 부모 실행 하나를 찾는다.
 *
 * <p>다른 에이전트에게 맡기는 도구가 위임을 시작하기 전에 부른다. 근거는 ADR-031 이다.
 *
 * <p>부모는 {@code status = RUNNING} 이고 {@code hermes_session_id} 가 뿌리 session 이며 주인이 토큰의
 * 사용자인 줄이 정확히 하나일 때만 정해진다. 없거나 둘 이상이면 가장 최근 줄을 고르지 않고 실패한다.
 * 동시 실행에서 부모를 추측하면 다른 turn 에 자식이 붙는다.
 *
 * <p>실패는 서명이 틀렸을 때와 같은 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 다. 밖에서 둘을 가르지 못하게
 * 해 남의 session 이 도는지 훑어 알아낼 수 없게 한다.
 */
@Service
@RequiredArgsConstructor
public class DelegationParentResolver {

    private static final Logger log = LoggerFactory.getLogger(DelegationParentResolver.class);

    private final AgentExecutionRepository executions;

    /**
     * @param user 토큰으로 정한 사용자. 남의 실행은 부모가 되지 못한다
     * @param rootSessionId {@code _fos_ctx} 에서 서명을 확인한 뿌리 session
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 도는 부모가 없거나 둘 이상일 때
     */
    public AgentExecution resolve(CurrentUser user, String rootSessionId) {
        // null 로 찾으면 Spring Data 가 "is null" 로 바꿔 session 이 비어 있는 옛 줄을 부모로 고를 수 있다.
        if (rootSessionId == null || rootSessionId.isBlank()) throw reject(user, "뿌리 session 이 비었다");
        List<AgentExecution> running = executions.findTop2ByHermesSessionIdAndStatusAndUserId(
                rootSessionId, ExecutionStatus.RUNNING, user.id());
        if (running.size() == 1) return running.get(0);
        throw reject(user, running.isEmpty() ? "도는 실행이 없다" : "도는 실행이 둘 이상이다");
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 서명 실패와 같다. */
    private static ApiException reject(CurrentUser user, String reason) {
        log.warn("위임의 부모 실행을 정하지 못했다 userId={} reason={}", user.id(), reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
