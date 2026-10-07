package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 판단 피드백 사건 한 줄을 새 트랜잭션으로 넣는다. {@link DecisionFeedbackRecorder} 만 부른다. */
@Component
@RequiredArgsConstructor
class FeedbackEventWriter {

    private final FeedbackEventRepository events;
    private final AgentExecutionRepository executions;

    /** 부르는 쪽 트랜잭션의 커밋 뒤에도 돌 수 있게 늘 새 트랜잭션을 연다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FeedbackEntry entry) {
        events.save(toEvent(entry));
    }

    /** 실행 번호가 있으면 트리 루트로 바꾸고, 대화를 모르면 그 실행의 대화를 쓴다. */
    private FeedbackEvent toEvent(FeedbackEntry entry) {
        Optional<AgentExecution> origin =
                entry.originExecutionId() == null ? Optional.empty() : executions.findById(entry.originExecutionId());
        // 실행 줄이 없으면 비운다. 없는 번호를 넣으면 FK 에 걸려 사건을 잃는다.
        Long rootExecutionId = origin.map(AgentExecution::treeRootId).orElse(null);
        Long conversationId = entry.conversationId() != null
                ? entry.conversationId()
                : origin.map(AgentExecution::conversationId).orElse(null);
        return FeedbackEvent.of(
                entry.userId(),
                entry.subjectType(),
                entry.subjectKey(),
                entry.eventType(),
                entry.actor(),
                conversationId,
                rootExecutionId,
                entry.sourceCheckId(),
                entry.autonomyDecisionId(),
                entry.subjectVersion(),
                entry.reasonCode(),
                entry.changedFields(),
                entry.occurredAt());
    }
}
