package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.orchestration.application.dto.SubagentRegistrationOptions;
import com.bifos.assistant.orchestration.application.dto.SubagentRegistrationResult;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.orchestration.infra.SubagentResultTexts;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
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
 * <li>값을 검사한다. 빈 값과 칸 길이를 넘는 값, 부모나 루트와 같은 자식, 실행 줄이나 대화가 쓰는 session 을
 * 거절한다. 최상위 session 은 압축 교체로 대화의 {@code hermes_session_id} 에만 남을 수 있어 대화도 본다.
 * <li>같은 자식의 줄이 이미 있고 루트와 부모가 요청과 같으면 부모를 풀지 않고 그대로 둔다. 첫 등록의 응답을
 * 잃은 재전송이 최상위 부모 실행이 끝난 뒤에 와도 받아들이기 위해서다.
 * <li>그 밖에는 부모를 풀어 origin 실행을 정한다. 이미 있는 줄이면 origin 을 견주어 판정하고, 없으면 저장한다.
 * </ol>
 *
 * <p>메서드 전체를 한 트랜잭션으로 묶지 않는다. 동시에 온 두 요청이 유일 제약이나 잠금에 걸리면 그 트랜잭션은
 * 롤백 전용이 되어 먼저 저장된 줄을 다시 읽지 못한다. 저장만 짧은 트랜잭션에 두고, 걸리면 그 밖에서 다시 읽어
 * 같은 규칙으로 판정한다. 다시 읽어도 줄이 없으면 거절한다. 등록은 요청마다 저장소를 읽고 쓰며 JVM 메모리에
 * 두지 않는다.
 *
 * <p>새로 저장하는 줄에는 끝 소식으로 깨울 자식인지({@code wake_eligible})도 한 번 정해 적는다. 플러그인이 끝 소식을 보낸다고 밝혔고,
 * 부모 등록 없이 루트로 풀린 최상위 자식이고, origin 이 대화 에이전트의 대화 turn(먼저 살펴보기 대화가 아님)일 때만 참이다.
 * 참이면 맡긴 일의 앞 40자를 봉해 함께 적는다(ADR-20261117 / native-delegation-wake).
 *
 * <p>실패 이유는 로그에만 남기고 session 값은 적지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubagentSessionRegistrar {

    /** {@code hermes_session_binding} 의 session 칸 길이다. 넘는 값은 저장 오류가 되기 전에 거절한다. */
    private static final int SESSION_ID_MAX_LENGTH = 128;

    /** 등록 줄에 적는 맡긴 일의 길이(code point)다. 알림 줄에 그대로 쓴다. */
    static final int GOAL_PREVIEW_CODE_POINTS = 40;

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final DelegationParentResolver parents;
    private final TransactionTemplate transactions;
    private final SubagentResultTexts texts;


    /** 선택 칸 없이 온 등록이다. 깨우지 않는다. */
    public SubagentRegistrationResult register(
        String profileName, String parentRootSessionId, String parentSessionId, String childSessionId) {
        return register(profileName, parentRootSessionId, parentSessionId, childSessionId, SubagentRegistrationOptions.NONE);
    }


    /**
     * @param profileName 등록 요청의 토큰이 증명한 profile
     * @param parentRootSessionId 서명을 확인한 루트 session
     * @param parentSessionId 이 하위 에이전트를 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다
     * @param childSessionId 등록할 하위 에이전트 session
     * @param options 서명하지 않는 선택 칸. 깨울 자식인지 정하는 데만 쓴다
     * @throws ApiException {@link ErrorCode#SESSION_BINDING_REJECTED}. 값이 틀렸거나 부모를 풀지 못했거나, 저장에 실패했는데 다시 읽은 줄도 없을 때.
     * {@link ErrorCode#SESSION_BINDING_CONFLICT}. 다른 origin 으로 이미 등록돼 있을 때
     */
    public SubagentRegistrationResult register(
        String profileName,
        String parentRootSessionId,
        String parentSessionId,
        String childSessionId,
        SubagentRegistrationOptions options) {
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

        ResolvedOrigin resolved = resolveOrigin(profileName, parentRootSessionId, parentSessionId);
        AgentExecution origin = resolved.execution();
        if (existing.isPresent()) {
            return judge(profileName, existing.get(), origin);
        }

        boolean wakeEligible = wakeEligible(resolved, options);
        try {
            transactions.executeWithoutResult(status -> {
                HermesSessionBinding saved = bindings.saveAndFlush(HermesSessionBinding.of(
                    profileName, childSessionId, origin, parentRootSessionId, parentSessionId, wakeEligible));
                if (wakeEligible) {
                    sealGoal(saved, options.childGoal());
                }
            });
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
     * 끝 소식으로 깨울 자식인가. 모두 맞아야 참이다.
     *
     * <ul>
     * <li>플러그인이 끝 소식을 보낸다고 밝혔다
     * <li>부모 등록이 아니라 루트로 풀었고 부모 하위 에이전트 번호가 없다. 부모 등록이 실패한 손자가 루트로 풀려도 번호가 있어 빠진다
     * <li>origin 이 부모 없는 실행이고 대화가 있다. 맡긴 에이전트의 실행({@code agent_delegate})은 부모가 있어 빠진다
     * <li>그 대화가 먼저 살펴보기 대화가 아니고, origin 의 에이전트가 대화의 에이전트다. {@code @} 맡김 turn 은 빠진다
     * </ul>
     */
    private boolean wakeEligible(ResolvedOrigin resolved, SubagentRegistrationOptions options) {
        if (!options.reportsStop() || resolved.viaParentBinding() || !isBlank(options.parentSubagentId())) {
            return false;
        }
        AgentExecution origin = resolved.execution();
        if (origin.parentExecutionId() != null || origin.conversationId() == null) {
            return false;
        }
        Optional<Conversation> conversation = conversations.findById(origin.conversationId());
        return conversation.isPresent()
            && conversation.get().purpose() != ConversationPurpose.CHECK
            && conversation.get().agentId() != null
            && conversation.get().agentId().equals(origin.agentId());
    }


    /** 맡긴 일의 앞 40 code point 를 봉해 적는다. 암호화가 꺼져 있으면 평문이다. 맡긴 일이 비었으면 적지 않는다. */
    private void sealGoal(HermesSessionBinding saved, String childGoal) {
        if (isBlank(childGoal)) {
            return;
        }
        String preview = preview(childGoal.strip());
        Optional<SealedText> sealed = texts.seal(saved.id(), saved.userId(), SubagentResultTexts.GOAL_PREVIEW, preview);
        bindings.sealGoal(
            saved.id(),
            sealed.map(SealedText::content).orElse(preview),
            sealed.map(SealedText::keyId).orElse(null));
    }


    static String preview(String goal) {
        int end = goal.offsetByCodePoints(0, Math.min(GOAL_PREVIEW_CODE_POINTS, goal.codePointCount(0, goal.length())));
        return goal.substring(0, end);
    }


    /** 풀린 origin 실행과, 부모 session 의 등록으로 풀었는지다. */
    private record ResolvedOrigin(AgentExecution execution, boolean viaParentBinding) {
    }


    /**
     * 부모 session 의 등록이 있으면 그 origin 을, 없으면 루트에서 도는 실행 하나를 origin 으로 한다.
     *
     * <p>압축 교체된 최상위 session 이 만든 자식은 부모 등록이 없지만 루트가 같아 뒤쪽에서 풀린다.
     */
    private ResolvedOrigin resolveOrigin(String profileName, String parentRootSessionId, String parentSessionId) {
        Optional<HermesSessionBinding> parent = bindings.findByProfileNameAndSessionId(profileName, parentSessionId);
        if (parent.isPresent()) {
            if (!parent.get().rootSessionId().equals(parentRootSessionId)) {
                throw reject(profileName, "부모 등록의 루트가 서명한 루트와 다르다");
            }
            return new ResolvedOrigin(
                executions
                    .findById(parent.get().originExecutionId())
                    .orElseThrow(() -> reject(profileName, "부모 등록의 origin 실행이 없다")),
                true);
        }
        try {
            return new ResolvedOrigin(parents.resolve(profileName, parentRootSessionId), false);
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
