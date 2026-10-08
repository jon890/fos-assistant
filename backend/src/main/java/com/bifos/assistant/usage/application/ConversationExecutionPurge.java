package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.ExecutionSessionRef;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 대화의 실행 기록에서 본문을 지운다(ADR-20261008 / conversation-purge).
 *
 * <p>실행 줄과 사건 줄은 남긴다. 사용량 합계가 지운 대화의 실행도 센다. 지우는 것은 답 본문과 사건의 {@code detail} 이다.
 */
@Component
@RequiredArgsConstructor
public class ConversationExecutionPurge {

    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;
    private final SubagentUsageJobRepository usageJobs;

    /**
     * 그 대화의 실행이 모두 끝났고 자식 사용량도 다 읽었는가.
     *
     * <p>도는 실행이 있으면 그 session 을 지금 지워도 Hermes 가 이어 쓴다. 자식 사용량을 읽는 중이거나, 부모 실행은 끝났지만
     * 자식의 작업 줄이 아직 생기지 않았으면 session 을 지운 뒤 사용량을 잃는다. 셋 다 아니어야 지운다.
     */
    @Transactional(readOnly = true)
    public boolean settled(Long conversationId, Instant now) {
        return !executions.existsByConversationIdAndStatus(conversationId, ExecutionStatus.RUNNING)
                && !usageJobs.existsWaitingInConversation(conversationId)
                && !events.existsUnscheduledChildInConversation(
                        conversationId, now.minus(SubagentUsageReconciler.DISCOVERY_WINDOW));
    }

    /** 그 대화의 실행이 보낸 Hermes session 을 겹치지 않게 낸다. */
    @Transactional(readOnly = true)
    public List<ExecutionSessionRef> sessions(Long conversationId) {
        return executions.findSessionRefs(conversationId);
    }

    /** 답 본문과 사건의 {@code detail} 을 비운다. 대화 줄을 잠근 부르는 쪽의 트랜잭션 안에서만 돈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseBodies(Long conversationId) {
        executions.clearOutputsOf(conversationId);
        events.clearDetailsOf(conversationId);
    }
}
