package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 보관 기간({@code retention})이 지난 판단 피드백 사건을 지운다. */
@Slf4j
@Component
public class DecisionFeedbackCleaner {

    private final FeedbackEventRepository events;
    private final DecisionFeedbackProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. 일정이 같은 객체의 메서드를 부르므로 @Transactional 대신 지우는 쿼리의 트랜잭션을 여기서 연다.
    public DecisionFeedbackCleaner(
            FeedbackEventRepository events,
            DecisionFeedbackProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.events = events;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.decision-feedback.cleanup-cron}")
    public void runScheduled() {
        clean(clock.instant());
    }

    /**
     * {@code now} 에서 보관 기간보다 먼저 일어난 사건을 지운다.
     *
     * @return 지운 줄 수
     */
    public int clean(Instant now) {
        Integer result = transactions.execute(status -> events.deleteOccurredBefore(now.minus(properties.retention())));
        int deleted = result == null ? 0 : result;
        log.info("expired decision feedback events cleaned deleted={}", deleted);
        return deleted;
    }
}
