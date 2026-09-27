package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ArtifactStore.Removed;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 결과물 파일을 지운다.
 *
 * <p>기간은 파일이 마지막으로 바뀐 때부터 센다. 폴더 안의 파일을 하나씩 지우고, 지운 HTML 을 가리키는 행에
 * {@code deleted_at} 을 적는다. 행은 남긴다. 한 건이 실패해도 나머지를 계속한다. 근거는 ADR-027 에 있다.
 */
@Component
@RequiredArgsConstructor
public class ArtifactCleaner {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCleaner.class);

    private final ArtifactStore store;
    private final ChatArtifactRepository artifacts;
    private final ArtifactProperties properties;

    /** 첨부의 정리와 같은 시각에 돈다. 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    public void runScheduled() {
        cleanExpired(Instant.now());
    }

    /**
     * {@code now} 에 보관 기간이 지난 파일을 지운다.
     *
     * @return 지운 파일 수
     */
    public int cleanExpired(Instant now) {
        List<Removed> removed = store.deleteOlderThan(now.minus(Duration.ofDays(properties.retentionDays())));
        int failed = 0;
        for (Removed file : removed) {
            if (!file.isHtml()) {
                continue;
            }
            try {
                artifacts.markDeleted(file.conversationId(), file.path(), now);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("could not mark an expired artifact conversationId={}", file.conversationId(), ex);
            }
        }
        log.info("expired artifacts cleaned deleted={} unmarked={}", removed.size(), failed);
        return removed.size();
    }
}
