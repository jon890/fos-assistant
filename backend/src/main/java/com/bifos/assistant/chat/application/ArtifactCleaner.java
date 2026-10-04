package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ArtifactRemoved;
import com.bifos.assistant.chat.infra.ArtifactStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 결과물 파일을 지운다.
 *
 * <p>기간은 파일이 마지막으로 바뀐 때부터 센다. 폴더 안의 파일을 하나씩 지우고, 지운 HTML 을 가리키는 행에
 * {@code deleted_at} 을 적는다. 행은 남긴다. 한 건이 실패해도 나머지를 계속한다. 근거는 ADR-027 에 있다.
 *
 * <p>지운 뒤 행 쪽에서 한 번 더 맞춘다. 지운 표시가 없고 기간 시작 전에 만든 행 가운데 파일이 없는 것에 지운 시각을
 * 적는다. 근거는 {@code docs/backend/artifact.md} 의 「지운 표시를 다시 맞추기」 에 있다.
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
        Instant cutoff = now.minus(Duration.ofDays(properties.retentionDays()));
        List<ArtifactRemoved> removed = store.deleteOlderThan(cutoff);
        int failed = 0;
        for (ArtifactRemoved file : removed) {
            if (!file.isHtml()) {
                continue;
            }
            try {
                artifactWriter.markDeleted(file.conversationId(), file.path(), now, cutoff);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("could not mark an expired artifact conversationId={}", file.conversationId(), ex);
            }
        }
        int reconciled = reconcile(now, cutoff);
        log.info("expired artifacts cleaned deleted={} unmarked={} reconciled={}", removed.size(), failed, reconciled);
        return removed.size();
    }

    /**
     * 파일이 없는데 지운 표시가 없는 행에 지운 시각을 적는다.
     *
     * <p>지운 뒤 표시에 실패했거나 그 사이 프로세스가 멈춘 행이다. 다음 훑기는 있는 파일만 보므로 행 쪽에서 찾는다.
     * 파일이 없다고 확인된 경로만 적는다. 결과물 루트가 디렉터리가 아니면 {@link ArtifactStore#isMissing} 이 거짓이라
     * 아무것도 적지 않는다.
     *
     * @return 지운 시각을 적은 행 수
     */
    private int reconcile(Instant now, Instant cutoff) {
        List<ChatArtifact> active;
        try {
            active = artifactWriter.activeCreatedBefore(cutoff);
        } catch (RuntimeException ex) {
            log.warn("could not list artifacts to reconcile", ex);
            return 0;
        }
        Set<ArtifactKey> keys = new LinkedHashSet<>();
        for (ChatArtifact artifact : active) {
            keys.add(new ArtifactKey(artifact.conversationId(), artifact.path()));
        }
        int reconciled = 0;
        for (ArtifactKey key : keys) {
            try {
                if (store.isMissing(key.conversationId(), key.path())) {
                    reconciled += artifactWriter.markDeleted(key.conversationId(), key.path(), now, cutoff);
                }
            } catch (RuntimeException ex) {
                log.warn("could not reconcile a missing artifact conversationId={}", key.conversationId(), ex);
            }
        }
        return reconciled;
    }

    private record ArtifactKey(Long conversationId, String path) {}
}
