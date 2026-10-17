package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftCreationRequestRepository;
import com.bifos.assistant.chat.domain.entity.ContentDraftCreationRequest;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 본문과 key를 함께 지우며 확정된 DB 상태만 정리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentDraftCleaner {
    private final ContentDraftRepository drafts;
    private final ContentDraftCreationRequestRepository creationRequests;
    private final ContentDraftRevisionRepository revisions;
    private final ContentDraftAssetRepository assets;
    private final ContentDraftReferences references;
    private final ConversationRepository conversations;
    private final ChatContentMutationCoordinator mutations;
    private final Clock clock;


    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForAttachment(Long conversationId, Long attachmentId) {
        mutations.requireParticipant(conversationId);
        revisions.invalidateAttachment(attachmentId);
    }


    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForConversation(Long conversationId) {
        mutations.requireParticipant(conversationId);
        revisions.invalidateConversation(conversationId);
    }


    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    @EventListener(ApplicationReadyEvent.class)
    public void runScheduled() {
        cleanExpired(clock.instant());
    }


    public int cleanExpired(Instant now) {
        int changed = 0;
        for (var candidate : revisions.findCleanupCandidates(now)) {
            Long conversationId = (Long)candidate[0];
            try {
                changed += mutations.run((Long)candidate[1], new ChatContentMutationTarget(conversationId, List.of()), () -> {
                    var conversation = conversations.findById(conversationId).orElseThrow();
                    int count = 0;
                    for (var draft : drafts.findByConversationIdOrderByIdAsc(conversationId)) {
                        if (!draft.expiresAt().isAfter(now)) {
                            count += revisions.expire(draft.id());
                            creationRequests.findByDraftPublicId(draft.publicId()).forEach(ContentDraftCreationRequest::markGone);
                            continue;
                        }
                        if (conversation.deletedAt() != null || !draft.ownerUserId().equals(conversation.userId())) {
                            count += revisions.invalidateDraft(draft.id());
                            continue;
                        }
                        for (var row : revisions.findByDraftIdOrderByRevisionAsc(draft.id())) {
                            if (row.status() == ContentDraftRevisionStatus.VALID
                                && !references.valid(draft, assets.findByRevisionIdOrderByPositionAsc(row.id()), false)) {
                                count += revisions.invalidate(row.id());
                            }
                        }
                    }
                    return count;
                });
            } catch (RuntimeException ex) {
                log.warn("원고 정리 실패 conversationId={} error={}", conversationId, ex.getClass().getSimpleName());
            }
        }
        return changed;
    }
}
