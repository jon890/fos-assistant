package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.orchestration.application.dto.ToolGuardAnswer;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 안전 목록 밖의 도구 호출 하나가 사건 살펴보기 트리에서 왔는지 판정한다(ADR-20261105 / event-watch).
 *
 * <p>순서는 {@code docs/features/proactive/design.md} 의 「사건 실행의 도구 판정」 이 갖는다. 자식 등록, 루트 session 에서 도는 실행,
 * 점검 대화, 그 profile 에서 도는 사건 살펴보기를 차례로 본다. 사건 실행은 점검 대화에서만 돌기 때문에, 도는 실행으로 풀린 보통 대화와
 * 점검 대화의 사용자 turn 은 그 실행의 판정을 따른다.
 *
 * <p>{@code mcp} 패키지의 인증 주체를 모르게 하려고 profile 이름과 session 을 문자열로 받는다. 까닭은 로그에만 남기고 session 값은
 * 적지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventToolGuard {

    /** 사건 실행 트리에서 막을 때 모델이 받는 글이다. 붙은 플러그인의 읽기 도구로 이어 가게 한다. */
    static final String EVENT_MESSAGE = "이번 실행은 사람이 보지 않는 새 사건 살펴보기라 이 도구를 쓸 수 없다. 다시 부르지 말고 붙은 플러그인의 읽기 도구로 살펴본다.";
    /** 어느 실행인지 풀지 못해 막을 때의 글이다. */
    static final String UNRESOLVED_MESSAGE = "이 도구 호출의 실행 맥락을 확인하지 못해 막았다. 다시 부르지 않는다.";

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final ProactiveCheckGuard checks;


    /**
     * @param profileName 토큰이 증명한 profile
     * @param rootSessionId 서명을 확인한 루트 session
     * @param sessionId 서명을 확인한, 도구를 부른 session
     */
    public ToolGuardAnswer decide(String profileName, String rootSessionId, String sessionId) {
        Optional<HermesSessionBinding> binding = bindings.findByProfileNameAndSessionId(profileName, sessionId);
        if (binding.isPresent()) {
            if (!binding.get().rootSessionId().equals(rootSessionId)) {
                return blocked(profileName, UNRESOLVED_MESSAGE, "등록의 루트가 서명한 루트와 다르다");
            }
            Optional<AgentExecution> origin = executions.findById(binding.get().originExecutionId());
            if (origin.isEmpty()) {
                return blocked(profileName, UNRESOLVED_MESSAGE, "등록의 origin 실행이 없다");
            }
            return judge(profileName, origin.get(), "등록된 자식");
        }
        List<AgentExecution> running = executions.findTop2ByHermesSessionIdAndStatusAndProfileName(
            rootSessionId, ExecutionStatus.RUNNING, profileName);
        if (running.size() > 1) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "루트에서 도는 실행이 둘 이상이다");
        }
        if (running.size() == 1) {
            return judge(profileName, running.get(0), "루트에서 도는 실행");
        }
        if (conversations.existsSessionWithPurpose(profileName, sessionId, rootSessionId, ConversationPurpose.CHECK)) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "점검 대화의 session 인데 도는 실행이 없다");
        }
        if (checks.eventRunningOn(profileName)) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "모르는 session 이고 그 profile 에서 사건 살펴보기가 돈다");
        }
        return ToolGuardAnswer.allow();
    }


    private ToolGuardAnswer judge(String profileName, AgentExecution execution, String via) {
        if (checks.isEventTree(execution)) {
            return blocked(profileName, EVENT_MESSAGE, via + "가 사건 살펴보기 트리다");
        }
        return ToolGuardAnswer.allow();
    }


    private static ToolGuardAnswer blocked(String profileName, String message, String reason) {
        log.info("도구 호출을 막았다 profile={} reason={}", profileName, reason);
        return ToolGuardAnswer.block(message);
    }
}
