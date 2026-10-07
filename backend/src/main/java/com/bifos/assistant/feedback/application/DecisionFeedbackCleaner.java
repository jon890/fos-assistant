package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 보관 기간({@code retention})이 지난 판단 피드백 사건을 지운다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionFeedbackCleaner {

    private final FeedbackEventRepository events;
    private final DecisionFeedbackProperties properties;
    private final Clock clock;

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.decision-feedback.cleanup-cron}")
    @Transactional
    public void runScheduled() {
        clean(clock.instant());
    }

    /**
     * {@code now} 에서 보관 기간보다 먼저 일어난 사건을 지운다.
     *
     * @return 지운 줄 수
     */
    @Transactional
    public int clean(Instant now) {
        int deleted = events.deleteOccurredBefore(now.minus(properties.retention()));
        log.info("expired decision feedback events cleaned deleted={}", deleted);
        return deleted;
    }
}
