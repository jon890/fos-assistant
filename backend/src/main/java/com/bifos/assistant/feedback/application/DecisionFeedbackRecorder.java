package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 판단 피드백 기록에 사건을 덧붙인다(ADR-20261007 decision-feedback).
 *
 * <p>부르는 쪽의 트랜잭션이 있으면 커밋한 뒤에 새 트랜잭션({@code REQUIRES_NEW})으로 남긴다. 되돌려진 동작의 사건이 남지 않고, 기록이
 * 실패해도 사용자의 동작을 되돌리지 않는다. 기록은 관측용이라 실패를 부르는 쪽으로 던지지 않는다. 로그에는 사용자 번호, 종류, 예외 클래스
 * 이름만 낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionFeedbackRecorder {

    private final FeedbackEventWriter writer;
    private final FeedbackEventRepository events;

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
    @Transactional
    public int forgetConversation(Long userId, Long conversationId) {
        List<String> subjects = events.findSubjectKeysOfConversation(userId, conversationId);
        int count = events.deleteOfConversation(userId, conversationId);
        if (!subjects.isEmpty()) {
            count += events.deleteOfSubjects(userId, subjects);
        }
        return count;
    }

    private void write(FeedbackEntry entry) {
        try {
            writer.insert(entry);
        } catch (RuntimeException ex) {
            log.warn(
                    "decision feedback event failed userId={} type={} kind={}",
                    entry.userId(),
                    entry.eventType(),
                    ex.getClass().getSimpleName());
        }
    }
}
