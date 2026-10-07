package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.model.PendingApproval;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지금 화면에서 제안 항목을 숨기거나 미룬 판단 피드백 사건을 남긴다(ADR-20261007 decision-feedback).
 *
 * <p>제안의 원천(대화, 제안한 실행)을 사건에 채워 기록기가 지운 대화의 제안을 걸러내게 한다. 원천을 찾지 못한 항목은 남기지 않는다. 기록은
 * 관측용이라 원천을 읽다 실패해도 숨기기와 미루기를 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionFeedback {

    private final DecisionFeedbackRecorder feedback;
    private final FollowUpService followUps;
    private final MemoryService memories;
    private final ConnectorActionService actions;

    /** 제안이 아닌 항목이면 아무것도 하지 않는다. */
    public void controlled(
            CurrentUser user, AttentionCandidate candidate, FeedbackEventType type, String reason, Instant now) {
        FeedbackSubjectType subject = switch (candidate.trigger()) {
            case FOLLOW_UP_PROPOSED -> FeedbackSubjectType.FOLLOW_UP;
            case MEMORY_PROPOSED -> FeedbackSubjectType.MEMORY;
            case APPROVAL_PENDING -> FeedbackSubjectType.CONNECTOR_ACTION;
            default -> null;
        };
        if (subject == null || subject != FeedbackSubjectType.ofKey(candidate.itemKey())) {
            return;
        }
        FeedbackEntry base = FeedbackEntry.ofKey(user.id(), subject, candidate.itemKey(), type, FeedbackActor.USER, now)
                .reason(reason);
        Optional<FeedbackEntry> entry;
        try {
            entry = withOrigin(user, subject, idOf(candidate.itemKey()), base);
        } catch (RuntimeException ex) {
            log.warn("숨기거나 미룬 제안의 원천을 읽지 못했다 userId={} subject={}", user.id(), subject);
            return;
        }
        entry.ifPresent(feedback::record);
    }

    /** 제안의 대화와 제안한 실행을 채운다. 둘 다 모르면 지운 대화인지 가릴 수 없어 빈 값이다. */
    private Optional<FeedbackEntry> withOrigin(
            CurrentUser user, FeedbackSubjectType subject, String id, FeedbackEntry base) {
        Optional<FeedbackEntry> filled = switch (subject) {
            case FOLLOW_UP ->
                followUps.openAndProposedOf(user.id()).stream()
                        .filter(followUp -> followUp.publicId().toString().equals(id))
                        .findFirst()
                        .map(FollowUpSnapshot::conversationId)
                        .map(base::conversation);
            case MEMORY ->
                memories.proposalsOf(user).stream()
                        .filter(memory -> memory.id().toString().equals(id))
                        .findFirst()
                        .map(Memory::proposedByExecutionId)
                        .map(base::originExecution);
            case CONNECTOR_ACTION ->
                actions.pendingApprovalsOf(user).stream()
                        .filter(action -> action.actionId().equals(UUID.fromString(id)))
                        .findFirst()
                        .map(PendingApproval::conversationId)
                        .map(base::conversation);
            default -> Optional.empty();
        };
        return filled;
    }

    private static String idOf(String itemKey) {
        return itemKey.substring(itemKey.indexOf(':') + 1);
    }
}
