package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ArtifactRemoved;
import com.bifos.assistant.chat.infra.ArtifactStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ArtifactCleaner {

    private final ArtifactStore store;
    private final ChatArtifactWriter artifactWriter;
    private final ArtifactProperties properties;
    private final Clock clock;

    /** 첨부의 정리와 같은 시각에 돈다. 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.attachment.cleanup-cron}")
    public void runScheduled() {
        cleanExpired(clock.instant());
    }

    /**
     * {@code now} 에 보관 기간이 지난 파일을 지운다.
     *
     * @return 지운 파일 수
     */
    public int cleanExpired(Instant now) {
        List<ArtifactRemoved> removed = store.deleteOlderThan(now.minus(Duration.ofDays(properties.retentionDays())));
        int failed = 0;
        for (ArtifactRemoved file : removed) {
            if (!file.isHtml()) {
                continue;
            }
            try {
                artifactWriter.markDeleted(file.conversationId(), file.path(), now);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("could not mark an expired artifact conversationId={}", file.conversationId(), ex);
            }
        }
        log.info("expired artifacts cleaned deleted={} unmarked={}", removed.size(), failed);
        return removed.size();
    }
}
