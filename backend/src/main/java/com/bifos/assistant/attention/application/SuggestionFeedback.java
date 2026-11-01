package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.action.application.dto.PendingApproval;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지금 화면에서 항목을 숨기거나 미룰 때 반응 기록에 남길 사건을 만든다(ADR-20261101 / proactive-suggestion 의 순서 5).
 *
 * <p>제안 항목(할 일, Memory, 승인 줄)이면 그 제안의 열쇠와 종류로 사건을 만들고, 제안의 원천(대화, 제안한 실행)을
 * 채워 대화를 지울 때 함께 지워지게 한다. 제안이 아닌 항목은 {@code ATTENTION_ITEM} 으로 만든다. 원천을 읽다 실패하거나 찾지 못하면
 * 원천 없는 사건으로 둔다. 숨김과 미루기의 현재값을 이 사건에서 구하므로, 원천 때문에 사건을 버리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionFeedback {

    private final FollowUpService followUps;
    private final MemoryService memories;
    private final ConnectorActionService actions;


    /**
     * 숨기거나 미룬 항목의 사건이다. {@code stateKey} 는 숨김이면 숨긴 상태, 미루기면 그때 후보의 상태이고 {@code subject_version} 에 둔다.
     */
    public FeedbackEntry controlEntry(
        CurrentUser user,
        AttentionCandidate candidate,
        FeedbackEventType type,
        String reason,
        String stateKey,
        Instant now) {
        FeedbackSubjectType subject = switch (candidate.trigger()) {
            case FOLLOW_UP_PROPOSED -> FeedbackSubjectType.FOLLOW_UP;
            case MEMORY_PROPOSED -> FeedbackSubjectType.MEMORY;
            case APPROVAL_PENDING -> FeedbackSubjectType.CONNECTOR_ACTION;
            default -> null;
        };
        if (subject == null || subject != FeedbackSubjectType.ofKey(candidate.itemKey())) {
            return FeedbackEntry.ofKey(
                user.id(), FeedbackSubjectType.ATTENTION_ITEM, candidate.itemKey(), type, FeedbackActor.USER, now)
                .reason(reason)
                .version(stateKey);
        }
        FeedbackEntry base = FeedbackEntry.ofKey(user.id(), subject, candidate.itemKey(), type, FeedbackActor.USER, now)
            .reason(reason)
            .version(stateKey);
        try {
            return withOrigin(user, subject, idOf(candidate.itemKey()), base).orElse(base);
        } catch (RuntimeException ex) {
            log.warn("숨기거나 미룬 제안의 원천을 읽지 못했다 userId={} subject={}", user.id(), subject);
            return base;
        }
    }


    /** 제안의 대화와 제안한 실행을 채운다. 원천을 모르면 지운 대화인지 가릴 수 없어 빈 값이다. */
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
