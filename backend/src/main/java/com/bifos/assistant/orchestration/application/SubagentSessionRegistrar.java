package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hermes 하위 에이전트 session 하나를 그 session 을 낳은 FOS 실행(origin 실행)에 묶어 한 줄로 적는다.
 *
 * <p>근거는 ADR-037 이다. 부모 session 에 등록이 있으면 그 origin 을 잇고, 없으면 뿌리 session 에서 지금 도는
 * 실행 하나가 origin 이다. 같은 origin 으로 다시 온 등록은 그대로 두고, 다른 origin 이면 덮어쓰지 않고 거절한다.
 *
 * <p>메서드 전체를 한 트랜잭션으로 묶지 않는다. 동시에 온 두 요청이 유일 제약에 걸리면 그 트랜잭션은 롤백
 * 전용이 되어 먼저 저장된 줄을 다시 읽지 못한다. 저장만 짧은 트랜잭션에 두고, 제약에 걸리면 그 밖에서 다시
 * 읽어 같은 규칙으로 판정한다. 등록은 요청마다 저장소를 읽고 쓰며 JVM 메모리에 두지 않는다.
 *
 * <p>실패 이유는 로그에만 남기고 session 값은 적지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SubagentSessionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SubagentSessionRegistrar.class);

    /** {@code hermes_session_binding} 의 session 칸 길이다. 넘는 값은 저장 오류가 되기 전에 거절한다. */
    private static final int SESSION_ID_MAX_LENGTH = 128;

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final DelegationParentResolver parents;
    private final TransactionTemplate transactions;

    /**
     * @param profileName 등록 요청의 토큰이 증명한 profile
     * @param parentRootSessionId 서명을 확인한 뿌리 session
     * @param parentSessionId 이 하위 에이전트를 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다
     * @param childSessionId 등록할 하위 에이전트 session
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 값이 틀렸거나 부모를 풀지 못했을 때.
     *     {@link ErrorCode#SESSION_BINDING_CONFLICT}. 다른 origin 으로 이미 등록돼 있을 때
     */
    public SubagentRegistrationResult register(
            String profileName, String parentRootSessionId, String parentSessionId, String childSessionId) {
        if (isBlank(profileName)) throw reject(profileName, "profile 이 비었다");
        if (!isSessionId(parentRootSessionId) || !isSessionId(parentSessionId) || !isSessionId(childSessionId)) {
            throw reject(profileName, "session 값이 비었거나 길다");
        }
        if (childSessionId.equals(parentSessionId) || childSessionId.equals(parentRootSessionId)) {
            throw reject(profileName, "하위 에이전트 session 이 부모나 뿌리와 같다");
        }
        // 최상위 session 에 등록이 생기면 뒤 turn 의 호출이 앞 turn 의 실행에 묶인다.
        if (executions.existsByProfileNameAndHermesSessionId(profileName, childSessionId)) {
            throw reject(profileName, "실행 줄이 쓰는 session 이다");
        }

        AgentExecution origin = resolveOrigin(profileName, parentRootSessionId, parentSessionId);

        Optional<HermesSessionBinding> existing = bindings.findByProfileNameAndSessionId(profileName, childSessionId);
        if (existing.isPresent()) return judge(profileName, existing.get(), origin);

        try {
            transactions.executeWithoutResult(status -> bindings.saveAndFlush(HermesSessionBinding.of(
                    profileName, childSessionId, origin, parentRootSessionId, parentSessionId)));
        } catch (DataIntegrityViolationException ex) {
            // 동시에 온 다른 요청이 먼저 저장했다. 그 줄을 다시 읽어 같은 규칙으로 판정한다.
            HermesSessionBinding raced = bindings.findByProfileNameAndSessionId(profileName, childSessionId)
                    .orElseThrow(() -> ex);
            return judge(profileName, raced, origin);
        }
        return accepted(profileName, origin, SubagentRegistrationResult.CREATED);
    }

    /**
     * 부모 session 의 등록이 있으면 그 origin 을, 없으면 뿌리에서 도는 실행 하나를 origin 으로 한다.
     *
     * <p>압축 교체된 최상위 session 이 만든 자식은 부모 등록이 없지만 뿌리가 같아 뒤쪽에서 풀린다.
     */
    private AgentExecution resolveOrigin(String profileName, String parentRootSessionId, String parentSessionId) {
        Optional<HermesSessionBinding> parent = bindings.findByProfileNameAndSessionId(profileName, parentSessionId);
        if (parent.isPresent()) {
            if (!parent.get().rootSessionId().equals(parentRootSessionId)) {
                throw reject(profileName, "부모 등록의 뿌리가 서명한 뿌리와 다르다");
            }
            return executions.findById(parent.get().originExecutionId())
                    .orElseThrow(() -> reject(profileName, "부모 등록의 origin 실행이 없다"));
        }
        try {
            return parents.resolve(profileName, parentRootSessionId);
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.MCP_CALL_CONTEXT_INVALID) throw ex;
            throw reject(profileName, "부모 등록이 없고 뿌리에서 도는 실행 하나를 찾지 못했다");
        }
    }

    /** 이미 있는 줄이 같은 origin 이면 그대로 두고, 다르면 덮어쓰지 않고 거절한다. */
    private static SubagentRegistrationResult judge(
            String profileName, HermesSessionBinding existing, AgentExecution origin) {
        if (existing.originExecutionId().equals(origin.id())) {
            return accepted(profileName, origin, SubagentRegistrationResult.EXISTS);
        }
        log.warn("하위 에이전트 session 을 등록하지 못했다 profile={} reason={}", profileName, "다른 origin 으로 이미 등록됐다");
        throw new ApiException(ErrorCode.SESSION_BINDING_CONFLICT, "session binding conflicts");
    }

    private static SubagentRegistrationResult accepted(
            String profileName, AgentExecution origin, SubagentRegistrationResult result) {
        log.info("하위 에이전트 session 을 등록했다 profile={} originExecutionId={} result={}",
                profileName, origin.id(), result);
        return result;
    }

    /** 이유는 로그에만 남긴다. 밖으로 나가는 문구는 어느 이유든 같다. */
    private static ApiException reject(String profileName, String reason) {
        log.warn("하위 에이전트 session 을 등록하지 못했다 profile={} reason={}", profileName, reason);
        return new ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected");
    }

    private static boolean isSessionId(String value) {
        return !isBlank(value) && value.length() <= SESSION_ID_MAX_LENGTH;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
