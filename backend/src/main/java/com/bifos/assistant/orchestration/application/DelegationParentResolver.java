package com.bifos.assistant.orchestration.application;

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
 * 토큰이 증명한 profile 과 서명을 확인한 뿌리 session 으로, 지금 도는 부모 실행 하나를 찾는다.
 *
 * <p>사용자가 걸린 MCP 도구가 요청자를 정할 때 부른다. 근거는 ADR-031 과 ADR-032 다.
 *
 * <p>부모는 {@code status = RUNNING} 이고 {@code hermes_session_id} 가 뿌리 session 이며 {@code profile_name} 이
 * 토큰의 profile 인 줄이 정확히 하나일 때만 정해진다. 사용자로 먼저 거르지 않는다. 같은 profile 을 여러 사용자가
 * 함께 쓰므로 사용자는 찾은 부모에서 읽는다. 없거나 둘 이상이면 그 profile 의 가장 최근 줄을 고르지 않고
 * 실패한다. 동시 실행에서 부모를 추측하면 다른 사용자의 권한으로 돈다.
 *
 * <p>실패는 서명이 틀렸을 때와 같은 {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 다. 밖에서 둘을 나누지 못하게
 * 해 남의 session 이 도는지 훑어 알아낼 수 없게 한다. 이유는 로그에만 남기고 사용자 번호는 적지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DelegationParentResolver {

    private static final Logger log = LoggerFactory.getLogger(DelegationParentResolver.class);

    private final AgentExecutionRepository executions;

    /**
     * @param profileName 토큰이 증명한 profile. 다른 profile 의 실행은 부모가 되지 못한다
     * @param rootSessionId {@code _fos_ctx} 에서 서명을 확인한 뿌리 session
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. 도는 부모가 없거나 둘 이상일 때
     */
    public AgentExecution resolve(String profileName, String rootSessionId) {
        if (profileName == null || profileName.isBlank()) throw reject(profileName, "profile 이 비었다");
        // null 로 찾으면 Spring Data 가 "is null" 로 바꿔 session 이 비어 있는 옛 줄을 부모로 고를 수 있다.
        if (rootSessionId == null || rootSessionId.isBlank()) throw reject(profileName, "뿌리 session 이 비었다");
        List<AgentExecution> running = executions.findTop2ByHermesSessionIdAndStatusAndProfileName(
                rootSessionId, ExecutionStatus.RUNNING, profileName);
        if (running.size() == 1) return running.get(0);
        if (running.size() > 1) throw reject(profileName, "도는 실행이 둘 이상이다");
        if (executions.existsByHermesSessionIdAndStatus(rootSessionId, ExecutionStatus.RUNNING)) {
            throw reject(profileName, "profile 이 다르다");
        }
        // 옛 대화에서 압축 교체가 일어나 뿌리가 우리 기록과 달라진 경우도 여기 온다. 로그에서 찾을 표시를 붙인다.
        throw reject(profileName, "도는 실행이 없다 DELEGATION_CONTEXT_UNAVAILABLE");
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 서명 실패와 같다. */
    private static ApiException reject(String profileName, String reason) {
        log.warn("MCP 호출의 부모 실행을 정하지 못했다 profile={} reason={}", profileName, reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
