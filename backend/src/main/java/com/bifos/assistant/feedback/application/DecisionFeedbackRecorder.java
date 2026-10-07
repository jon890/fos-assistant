package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판단 피드백 기록에 사건을 덧붙인다(ADR-20261007 decision-feedback).
 *
 * <p>부르는 쪽의 트랜잭션이 있으면 커밋한 뒤에 새 트랜잭션({@code REQUIRES_NEW})으로 남긴다. 되돌려진 동작의 사건이 남지 않고, 기록이
 * 실패해도 사용자의 동작을 되돌리지 않는다. 기록은 관측용이라 실패를 부르는 쪽으로 던지지 않는다. 로그에는 사용자 번호, 종류, 예외 클래스
 * 이름만 낸다.
 */
@Slf4j
@Component
public class DecisionFeedbackRecorder {

    private final FeedbackEventRepository events;
    private final AgentExecutionRepository executions;
    private final TransactionTemplate separate;
    private final TransactionTemplate joined;

    public DecisionFeedbackRecorder(
            FeedbackEventRepository events,
            AgentExecutionRepository executions,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.executions = executions;
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.joined = new TransactionTemplate(transactionManager);
    }

    /** 사건 하나를 남긴다. 트랜잭션 안에서 부르면 커밋한 뒤에 남기고, 되돌려지면 남기지 않는다. */
    public void record(FeedbackEntry entry) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(entry);
                }
            });
            return;
        }
        write(entry);
    }

    /**
     * 그 대화에 묶인 사건과, 그 사건이 가리키는 제안의 다른 사건을 모두 지운다. 대화를 지울 때 그 트랜잭션 안에서 부른다.
     *
     * @return 지운 줄 수
     */
    public int forgetConversation(Long userId, Long conversationId) {
        Integer deleted = joined.execute(status -> {
            List<String> subjects = events.findSubjectKeysOfConversation(userId, conversationId);
            int count = events.deleteOfConversation(userId, conversationId);
            if (!subjects.isEmpty()) {
                count += events.deleteOfSubjects(userId, subjects);
            }
            return count;
        });
        return deleted == null ? 0 : deleted;
    }

    private void write(FeedbackEntry entry) {
        try {
            separate.executeWithoutResult(status -> events.save(toEvent(entry)));
        } catch (RuntimeException ex) {
            log.warn(
                    "decision feedback event failed userId={} type={} kind={}",
                    entry.userId(),
                    entry.eventType(),
                    ex.getClass().getSimpleName());
        }
    }

    /** 실행 번호가 있으면 트리 루트로 바꾸고, 대화를 모르면 그 실행의 대화를 쓴다. */
    private FeedbackEvent toEvent(FeedbackEntry entry) {
        Optional<AgentExecution> origin =
                entry.originExecutionId() == null ? Optional.empty() : executions.findById(entry.originExecutionId());
        Long rootExecutionId = origin.map(AgentExecution::treeRootId).orElse(entry.originExecutionId());
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
