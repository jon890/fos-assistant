package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.config.AttentionProperties;
import com.bifos.assistant.attention.infra.AttentionEventRepository;
import com.bifos.assistant.feedback.application.ReactionLedger;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보관 기간({@code event-retention})이 지난 지표 사건을 지운다. 반응 기록의 노출, 열기, 동작 사건과, 옛 {@code attention_event} 에 남은
 * 줄이다. 숨기기와 미루기의 제어 사건은 지우지 않는다. 그 사건은 {@code DecisionFeedbackCleaner} 가 지금도 걸린 것만 남기고 지운다.
 */
@Slf4j
@Component
public class AttentionEventCleaner {

    private final AttentionEventRepository events;
    private final ReactionLedger ledger;
    private final AttentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;


    // 생성자를 직접 쓴다. 일정이 같은 객체의 메서드를 부르므로 @Transactional 대신 지우는 쿼리의 트랜잭션을 여기서 연다.
    public AttentionEventCleaner(
        AttentionEventRepository events,
        ReactionLedger ledger,
        AttentionProperties properties,
        PlatformTransactionManager transactionManager,
        Clock clock) {
        this.events = events;
        this.ledger = ledger;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }


    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.attention.cleanup-cron}")
    public void runScheduled() {
        clean(clock.instant());
    }


    /**
     * {@code now} 에서 보관 기간보다 먼저 남긴 사건을 지운다.
     *
     * @return 지운 줄 수
     */
    public int clean(Instant now) {
        Instant before = now.minus(properties.eventRetention());
        // 옛 표에는 더 쓰는 곳이 없다. 남은 줄이 보관 기간을 지나며 비워지게 둔다.
        Integer legacy = transactions.execute(status -> events.deleteCreatedBefore(before));
        Integer views = transactions.execute(status -> ledger.deleteAttentionViewsBefore(before));
        int deleted = (legacy == null ? 0 : legacy) + (views == null ? 0 : views);
        log.info("expired attention events cleaned deleted={}", deleted);
        return deleted;
    }
}
