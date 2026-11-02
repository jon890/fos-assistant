package com.bifos.assistant.chat.contentdraft.application;

import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraft;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftAsset;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftCreationRequestRepository;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationRequest;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ContentDraftReferences references;
    private final ConversationRepository conversations;
    private final ChatContentMutationCoordinator mutations;
    private final Clock clock;


    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForAttachment(Long conversationId, Long attachmentId) {
        mutations.requireParticipant(conversationId);
        for (var row : revisions.findValidForConversation(conversationId)) {
            if (row.assets().stream().anyMatch(asset -> attachmentId.equals(asset.attachmentId()))) {
                revisions.invalidate(row.id());
            }
        }
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
        for (var candidate : candidates(now).entrySet()) {
            Long conversationId = candidate.getKey();
            try {
                changed += mutations.run(candidate.getValue(), new ChatContentMutationTarget(conversationId, List.of()), () -> {
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
                                && !references.valid(draft, row.assets(), false)) {
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


    /**
     * 정리할 대화와 그 주인이다. 기한·대화 상태는 쿼리가 고르고, 사진 참조는 JSON 칸이라 잠그기 전에 여기서 한 번 확인한다. 잠근
     * 뒤에 같은 확인을 다시 한다.
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Long> candidates(Instant now) {
        var result = new LinkedHashMap<Long, Long>();
        for (var row : revisions.findCleanupCandidates(now)) {
            result.put((Long)row[0], (Long)row[1]);
        }
        for (var row : revisions.findValidWithDraft(now)) {
            var draft = (ContentDraft)row[2];
            var assets = (List<ContentDraftAsset>)row[1];
            if (!result.containsKey(draft.conversationId()) && (assets == null || !references.valid(draft, assets, false))) {
                result.put(draft.conversationId(), (Long)row[3]);
            }
        }
        return result;
    }
}
