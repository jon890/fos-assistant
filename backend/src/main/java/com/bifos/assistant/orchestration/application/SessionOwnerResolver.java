package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 호출 하나가 어느 FOS 실행(origin 실행)에 속하는지 정한다. 판정 순서는 ADR-037 의 「판정 순서」 다.
 *
 * <p>호출한 session 의 등록이 있으면 그 줄의 origin 실행이다. origin 실행이 끝났어도 쓰되, origin 실행이나 그
 * 실행 트리의 루트 실행이 {@code CANCELLED} 면 거절한다. 하위 에이전트는 부모 turn 이 끝난 뒤에도 돌 수 있고, 그때
 * 최근 실행을 고르면 다른 turn 이나 다른 사용자로 돈다. 중지해도 그 하위 에이전트는 계속 돌 수 있어, 사용자가 멈춘
 * turn 의 권한을 쓰지 못하게 막는다. 흐름의 자식은 제 session 으로 돌아 origin 이 되고, 흐름을 멈출 때 이미 끝난
 * 자식은 {@code CANCELLED} 가 되지 않으므로 루트도 본다. 루트와 origin 사이의 중간 실행은 보지 않는다.
 * {@code FAILED} 는 서버가 다시 떠 끝낸 경우가 섞여 있어 그대로 쓴다.
 * 등록이 없으면 호출한 session 이 루트 session 과 같을 때만 루트에서 도는 실행 하나를 찾는다. 둘이 다른데 등록이
 * 없으면 등록이 빠진 하위 에이전트와 압축 교체된 최상위 session 을 나눌 수 없어 추측하지 않고 거절한다.
 *
 * <p>{@code mcp} 패키지를 모르게 하려고 문자열 셋을 받는다. 실패는 서명이 틀렸을 때와 같은
 * {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 하나다. 이유는 로그에만 남기고 사용자 번호와 session 값은 적지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionOwnerResolver {

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final DelegationParentResolver parents;

    /**
     * @param profileName 토큰이 증명한 profile
     * @param rootSessionId {@code _fos_ctx} 에서 서명을 확인한 루트 session
     * @param sessionId {@code _fos_ctx} 에서 서명을 확인한, 도구를 부른 session
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID}. origin 실행을 정하지 못했을 때
     */
    public AgentExecution resolve(String profileName, String rootSessionId, String sessionId) {
        if (isBlank(profileName) || isBlank(rootSessionId) || isBlank(sessionId)) {
            throw reject(profileName, "profile 이나 session 이 비었다");
        }
        Optional<HermesSessionBinding> binding = bindings.findByProfileNameAndSessionId(profileName, sessionId);
        if (binding.isPresent()) {
            if (!binding.get().rootSessionId().equals(rootSessionId)) {
                throw reject(profileName, "등록의 루트가 서명한 루트와 다르다");
            }
            AgentExecution origin = executions
                    .findById(binding.get().originExecutionId())
                    .orElseThrow(() -> reject(profileName, "등록의 origin 실행이 없다"));
            // 중지해도 Hermes 하위 에이전트는 계속 돌 수 있다. 사용자가 멈춘 turn 의 권한을 그 자식이 쓰지 못하게 한다.
            if (origin.status() == ExecutionStatus.CANCELLED) {
                throw reject(profileName, "중지된 origin 실행이다 ORIGIN_CANCELLED");
            }
            if (rootCancelled(origin)) {
                throw reject(profileName, "origin 실행 트리의 루트가 중지됐다 ORIGIN_CANCELLED");
            }
            return origin;
        }
        if (sessionId.equals(rootSessionId)) {
            return parents.resolve(profileName, rootSessionId);
        }
        throw reject(profileName, "등록이 없는 하위 session 이다 SUBAGENT_SESSION_UNREGISTERED");
    }

    /** 루트 자신은 {@code root_execution_id} 가 비어 있다. 루트 줄이 없으면 origin 판정만 따른다. */
    private boolean rootCancelled(AgentExecution origin) {
        Long rootId = origin.rootExecutionId();
        if (rootId == null || rootId.equals(origin.id())) {
            return false;
        }
        return executions
                .findById(rootId)
                .map(root -> root.status() == ExecutionStatus.CANCELLED)
                .orElse(false);
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 서명 실패와 같다. */
    private static ApiException reject(String profileName, String reason) {
        log.warn("MCP 호출의 origin 실행을 정하지 못했다 profile={} reason={}", profileName, reason);
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
