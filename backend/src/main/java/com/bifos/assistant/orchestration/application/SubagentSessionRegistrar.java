package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hermes 하위 에이전트 session 하나를 그 session 을 낳은 FOS 실행(origin 실행)에 묶어 한 줄로 적는다.
 *
 * <p>근거는 ADR-037 이다. 부모 session 에 등록이 있으면 그 origin 을 잇고, 없으면 루트 session 에서 지금 도는
 * 실행 하나가 origin 이다. 같은 origin 으로 다시 온 등록은 그대로 두고, 다른 origin 이면 덮어쓰지 않고 거절한다.
 *
 * <p>판정은 이 순서로 한다.
 *
 * <ol>
 *   <li>값을 검사한다. 빈 값과 칸 길이를 넘는 값, 부모나 루트와 같은 자식, 실행 줄이나 대화가 쓰는 session 을
 *       거절한다. 최상위 session 은 압축 교체로 대화의 {@code hermes_session_id} 에만 남을 수 있어 대화도 본다.
 *   <li>같은 자식의 줄이 이미 있고 루트와 부모가 요청과 같으면 부모를 풀지 않고 그대로 둔다. 첫 등록의 응답을
 *       잃은 재전송이 최상위 부모 실행이 끝난 뒤에 와도 받아들이기 위해서다.
 *   <li>그 밖에는 부모를 풀어 origin 실행을 정한다. 이미 있는 줄이면 origin 을 견주어 판정하고, 없으면 저장한다.
 * </ol>
 *
 * <p>메서드 전체를 한 트랜잭션으로 묶지 않는다. 동시에 온 두 요청이 유일 제약이나 잠금에 걸리면 그 트랜잭션은
 * 롤백 전용이 되어 먼저 저장된 줄을 다시 읽지 못한다. 저장만 짧은 트랜잭션에 두고, 걸리면 그 밖에서 다시 읽어
 * 같은 규칙으로 판정한다. 다시 읽어도 줄이 없으면 거절한다. 등록은 요청마다 저장소를 읽고 쓰며 JVM 메모리에
 * 두지 않는다.
 *
 * <p>실패 이유는 로그에만 남기고 session 값은 적지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubagentSessionRegistrar {

    /** {@code hermes_session_binding} 의 session 칸 길이다. 넘는 값은 저장 오류가 되기 전에 거절한다. */
    private static final int SESSION_ID_MAX_LENGTH = 128;

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final DelegationParentResolver parents;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * @param profileName 등록 요청의 토큰이 증명한 profile
     * @param parentRootSessionId 서명을 확인한 루트 session
     * @param parentSessionId 이 하위 에이전트를 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다
     * @param childSessionId 등록할 하위 에이전트 session
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 값이 틀렸거나 부모를 풀지 못했거나, 저장에 실패했는데 다시 읽은 줄도 없을 때.
     *     {@link ErrorCode#SESSION_BINDING_CONFLICT}. 다른 origin 으로 이미 등록돼 있을 때
     */
    public SubagentRegistrationResult register(
            String profileName, String parentRootSessionId, String parentSessionId, String childSessionId) {
        if (isBlank(profileName)) {
            throw reject(profileName, "profile 이 비었다");
        }
        if (!isSessionId(parentRootSessionId) || !isSessionId(parentSessionId) || !isSessionId(childSessionId)) {
            throw reject(profileName, "session 값이 비었거나 길다");
        }
        if (childSessionId.equals(parentSessionId) || childSessionId.equals(parentRootSessionId)) {
            throw reject(profileName, "하위 에이전트 session 이 부모나 루트와 같다");
        }
        // 최상위 session 에 등록이 생기면 뒤 turn 의 호출이 앞 turn 의 실행에 묶인다.
        if (executions.existsByProfileNameAndHermesSessionId(profileName, childSessionId)) {
            throw reject(profileName, "실행 줄이 쓰는 session 이다");
        }
        // 압축 교체된 최상위 session 은 실행 줄에 없고 대화에만 남는다. 등록되면 그 호출이 옛 origin 으로 통과한다.
        if (conversations.existsByHermesSessionIdOrHermesRootSessionId(childSessionId, childSessionId)) {
            throw reject(profileName, "대화가 쓰는 session 이다");
        }

        Optional<HermesSessionBinding> existing = bindings.findByProfileNameAndSessionId(profileName, childSessionId);
        if (existing.isPresent() && sameParents(existing.get(), parentRootSessionId, parentSessionId)) {
            return accepted(profileName, existing.get().originExecutionId(), SubagentRegistrationResult.EXISTS);
        }

        AgentExecution origin = resolveOrigin(profileName, parentRootSessionId, parentSessionId);
        if (existing.isPresent()) {
            return judge(profileName, existing.get(), origin);
        }

        try {
            transactions.executeWithoutResult(status -> bindings.saveAndFlush(HermesSessionBinding.of(
                    profileName, childSessionId, origin, parentRootSessionId, parentSessionId, clock.instant())));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
            // 동시에 온 다른 요청이 먼저 저장했거나 잠금에서 밀렸다. 그 줄을 다시 읽어 같은 규칙으로 판정한다.
            HermesSessionBinding raced = bindings.findByProfileNameAndSessionId(profileName, childSessionId)
                    .orElseThrow(() -> reject(profileName, "저장에 실패했고 다시 읽은 줄도 없다"));
            return judge(profileName, raced, origin);
        }
        return accepted(profileName, origin.id(), SubagentRegistrationResult.CREATED);
    }

    private static boolean sameParents(
            HermesSessionBinding existing, String parentRootSessionId, String parentSessionId) {
        return existing.rootSessionId().equals(parentRootSessionId)
                && existing.parentSessionId().equals(parentSessionId);
    }

    /**
     * 부모 session 의 등록이 있으면 그 origin 을, 없으면 루트에서 도는 실행 하나를 origin 으로 한다.
     *
     * <p>압축 교체된 최상위 session 이 만든 자식은 부모 등록이 없지만 루트가 같아 뒤쪽에서 풀린다.
     */
    private AgentExecution resolveOrigin(String profileName, String parentRootSessionId, String parentSessionId) {
        Optional<HermesSessionBinding> parent = bindings.findByProfileNameAndSessionId(profileName, parentSessionId);
        if (parent.isPresent()) {
            if (!parent.get().rootSessionId().equals(parentRootSessionId)) {
                throw reject(profileName, "부모 등록의 루트가 서명한 루트와 다르다");
            }
            return executions
                    .findById(parent.get().originExecutionId())
                    .orElseThrow(() -> reject(profileName, "부모 등록의 origin 실행이 없다"));
        }
        try {
            return parents.resolve(profileName, parentRootSessionId);
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.MCP_CALL_CONTEXT_INVALID) {
                throw ex;
            }
            throw reject(profileName, "부모 등록이 없고 루트에서 도는 실행 하나를 찾지 못했다");
        }
    }

    /** 이미 있는 줄이 같은 origin 이면 그대로 두고, 다르면 덮어쓰지 않고 거절한다. */
    private static SubagentRegistrationResult judge(
            String profileName, HermesSessionBinding existing, AgentExecution origin) {
        if (existing.originExecutionId().equals(origin.id())) {
            return accepted(profileName, origin.id(), SubagentRegistrationResult.EXISTS);
        }
        log.warn("하위 에이전트 session 을 등록하지 못했다 profile={} reason={}", profileName, "다른 origin 으로 이미 등록됐다");
        throw new ApiException(ErrorCode.SESSION_BINDING_CONFLICT, "session binding conflicts");
    }

    private static SubagentRegistrationResult accepted(
            String profileName, Long originExecutionId, SubagentRegistrationResult result) {
        log.info(
                "하위 에이전트 session 을 등록했다 profile={} originExecutionId={} result={}",
                profileName,
                originExecutionId,
                result);
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
