package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 첨부의 파일을 지운다.
 *
 * <p>행은 남기고 {@code deleted_at} 만 적는다. 메시지에 묶이지 않은 첨부도 같은 기간으로 함께 지운다.
 * 한 건이 실패해도 나머지를 계속한다. 하나 때문에 그날 치가 통째로 멈추면 디스크가 계속 찬다.
 */
@Component
@RequiredArgsConstructor
public class AttachmentCleaner {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleaner.class);

    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    public void runScheduled() {
        cleanExpired(Instant.now());
    }

    /**
     * {@code now} 에 기간이 지났고 아직 지우지 않은 첨부를 지운다.
     *
     * @return 지운 건수
     */
    public int cleanExpired(Instant now) {
        List<ChatAttachment> expired =
                attachments.findByExpiresAtBeforeAndDeletedAtIsNullOrderByIdAsc(now);
        int deleted = 0;
        int failed = 0;
        for (ChatAttachment attachment : expired) {
            try {
                store.delete(attachment);
                attachment.markDeleted(now);
                attachments.save(attachment);
                deleted++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("could not delete an expired attachment id={}", attachment.id(), ex);
            }
        }
        log.info("expired attachments cleaned deleted={} failed={}", deleted, failed);
        return deleted;
    }
}
