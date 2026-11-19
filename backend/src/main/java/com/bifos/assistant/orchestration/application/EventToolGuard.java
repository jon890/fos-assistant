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
 * <p>사람이 없는 실행(예약 작업, 먼저 살펴보기, 그 결과를 전하는 turn)의 그림 만들기도 여기서 막는다(ADR-20261119 /
 * image-gen-defaults). 실행을 찾지 못한 호출은 Control Plane 밖에서 연 session 이라 이 판정으로 막지 않는다.
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
    /** 사람이 없는 실행에서 그림 만들기를 막을 때 모델이 받는 글이다(스펙 generated-image REQ14). */
    static final String IMAGE_MESSAGE = "이번 실행은 사람이 직접 부탁한 것이 아니라(예약 작업, 먼저 살펴보기, 그 결과를 전하는 실행) 그림을 만들 수 없다. "
        + "다시 부르지 말고 결과에 그림은 만들지 못했다고 적는다. 사용자가 대화에서 직접 부탁하면 만들 수 있다.";
    /** Hermes 의 그림 만들기 도구다. 구독 한도를 쓰므로 사람이 연 실행에서만 부른다. */
    static final String IMAGE_TOOL = "image_generate";
    /** 어느 실행인지 풀지 못해 막을 때의 글이다. */
    static final String UNRESOLVED_MESSAGE = "이 도구 호출의 실행 맥락을 확인하지 못해 막았다. 다시 부르지 않는다.";

    private final HermesSessionBindingRepository bindings;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final ProactiveCheckGuard checks;
    private final SessionOwnerResolver owners;


    /**
     * @param profileName 토큰이 증명한 profile
     * @param rootSessionId 서명을 확인한 루트 session
     * @param sessionId 서명을 확인한, 도구를 부른 session
     * @param hermesTool 서명을 확인한 도구의 등록 이름
     */
    public ToolGuardAnswer decide(String profileName, String rootSessionId, String sessionId, String hermesTool) {
        Optional<HermesSessionBinding> binding = bindings.findByProfileNameAndSessionId(profileName, sessionId);
        if (binding.isPresent()) {
            if (!binding.get().rootSessionId().equals(rootSessionId)) {
                return blocked(profileName, UNRESOLVED_MESSAGE, "등록의 루트가 서명한 루트와 다르다");
            }
            Optional<AgentExecution> origin = executions.findById(binding.get().originExecutionId());
            if (origin.isEmpty()) {
                return blocked(profileName, UNRESOLVED_MESSAGE, "등록의 origin 실행이 없다");
            }
            return judge(profileName, origin.get(), hermesTool, "등록된 자식");
        }
        List<AgentExecution> running = executions.findTop2ByHermesSessionIdAndStatusAndProfileName(
            rootSessionId, ExecutionStatus.RUNNING, profileName);
        if (running.size() > 1) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "루트에서 도는 실행이 둘 이상이다");
        }
        if (running.size() == 1) {
            return judge(profileName, running.get(0), hermesTool, "루트에서 도는 실행");
        }
        if (conversations.existsSessionWithPurpose(profileName, sessionId, rootSessionId, ConversationPurpose.CHECK)) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "점검 대화의 session 인데 도는 실행이 없다");
        }
        if (checks.eventRunningOn(profileName)) {
            return blocked(profileName, UNRESOLVED_MESSAGE, "모르는 session 이고 그 profile 에서 사건 살펴보기가 돈다");
        }
        return ToolGuardAnswer.allow();
    }


    private ToolGuardAnswer judge(String profileName, AgentExecution execution, String hermesTool, String via) {
        if (checks.isEventTree(execution)) {
            return blocked(profileName, EVENT_MESSAGE, via + "가 사건 살펴보기 트리다");
        }
        // 트리 루트로 본다. 사람이 없는 실행이 맡긴 하위 실행과 그 결과를 전하는 turn 도 여기서 막힌다.
        if (IMAGE_TOOL.equals(hermesTool) && owners.unattendedTree(execution)) {
            return blocked(profileName, IMAGE_MESSAGE, via + "가 사람이 없는 실행인데 그림을 만들려 했다");
        }
        return ToolGuardAnswer.allow();
    }


    private static ToolGuardAnswer blocked(String profileName, String message, String reason) {
        log.info("도구 호출을 막았다 profile={} reason={}", profileName, reason);
        return ToolGuardAnswer.block(message);
    }
}
