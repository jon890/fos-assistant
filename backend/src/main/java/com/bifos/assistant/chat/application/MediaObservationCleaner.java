package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
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

/** 사용자별 삭제 장벽 안에서 관찰과 FK로 연결한 alias를 함께 지운다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaObservationCleaner {
    private final MediaObservationRepository observations;
    private final ChatContentMutationCoordinator mutations;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForAttachment(Long conversationId, Long attachmentId) {
        mutations.requireParticipant(conversationId);
        observations.deleteForAttachment(conversationId, attachmentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForConversation(Long conversationId) {
        mutations.requireParticipant(conversationId);
        observations.deleteForConversation(conversationId);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    public void runScheduled() {
        cleanExpired(clock.instant());
    }

    public int cleanExpired(Instant now) {
        int deleted = 0;
        for (Object[] candidate : observations.findCleanupCandidates(now)) {
            Long conversationId = (Long) candidate[0];
            Long attachmentId = (Long) candidate[1];
            try {
                deleted += mutations.run(
                        (Long) candidate[2],
                        new ChatContentMutationTarget(conversationId, List.of(attachmentId)),
                        () -> {
                            boolean stillBlocked = observations.findCleanupCandidates(now).stream()
                                    .anyMatch(row -> conversationId.equals(row[0]) && attachmentId.equals(row[1]));
                            return stillBlocked ? observations.deleteForAttachment(conversationId, attachmentId) : 0;
                        });
            } catch (RuntimeException ex) {
                log.warn(
                        "관찰 정리 실패 attachmentId={} error={}",
                        attachmentId,
                        ex.getClass().getSimpleName());
            }
        }
        return deleted;
    }
}
