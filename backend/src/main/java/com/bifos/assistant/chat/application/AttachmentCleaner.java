package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 첨부의 파일을 지운다.
 *
 * <p>파일 작업 전에 요청 시각을 커밋하고 완료 뒤 {@code deleted_at}을 적는다. 행은 남기며 실패한 요청도 재시도한다.
 * 메시지에 묶이지 않은 첨부도 같은 기간으로 함께 지운다.
 * 한 건이 실패해도 나머지를 계속한다. 하나 때문에 그날 치가 통째로 멈추면 디스크가 계속 찬다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentCleaner {

    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final Clock clock;
    private final ChatContentMutationCoordinator mutations;

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    @EventListener(ApplicationReadyEvent.class)
    public void runScheduled() {
        cleanExpired(clock.instant());
    }

    /**
     * {@code now} 에 기간이 지났고 아직 지우지 않은 첨부를 지운다.
     *
     * @return 지운 건수
     */
    public int cleanExpired(Instant now) {
        List<ChatAttachment> expired = attachments.findByExpiresAtBeforeAndDeletedAtIsNullOrderByIdAsc(now);
        int deleted = 0;
        int failed = 0;
        for (ChatAttachment attachment : expired) {
            try {
                if (delete(
                        attachment,
                        now,
                        () -> attachments
                                .findById(attachment.id())
                                .filter(it -> it.deletionRequestedAt() != null
                                        || !it.expiresAt().isAfter(now))
                                .orElse(null))) {
                    deleted++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn(
                        "첨부 정리 실패 attachmentId={} error={}",
                        attachment.id(),
                        ex.getClass().getSimpleName());
            }
        }
        log.info("expired attachments cleaned deleted={} failed={}", deleted, failed);
        return deleted;
    }

    /** 요청 커밋, 파일 작업, 완료 커밋을 나눈다. 파일이 이미 없으면 삭제도 성공한다. */
    public boolean delete(ChatAttachment candidate, Instant now) {
        return delete(candidate, now, () -> attachments.findById(candidate.id()).orElseThrow());
    }

    public boolean delete(ChatAttachment candidate, Instant now, Supplier<ChatAttachment> authorized) {
        var target = new ChatContentMutationTarget(candidate.conversationId(), List.of(candidate.id()));
        ChatAttachment current = mutations.run(candidate.uploadedByUserId(), target, () -> {
            ChatAttachment row = authorized.get();
            if (row == null) {
                return null;
            }
            if (row.deletedAt() != null) {
                return null;
            }
            row.requestDeletion(now);
            return row;
        });
        if (current == null) {
            return false;
        }
        store.delete(current);
        mutations.run(current.uploadedByUserId(), target, () -> {
            attachments.findById(current.id()).orElseThrow().markDeleted(now);
            return null;
        });
        return true;
    }
}
