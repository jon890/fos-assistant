package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.infra.AttentionEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 보관 기간({@code event-retention})이 지난 지표 사건을 지운다. 숨기기와 미루기의 줄은 지우지 않는다. */
@Slf4j
@Component
public class AttentionEventCleaner {

    private final AttentionEventRepository events;
    private final AttentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. 일정이 같은 객체의 메서드를 부르므로 @Transactional 대신 지우는 쿼리의 트랜잭션을 여기서 연다.
    public AttentionEventCleaner(
            AttentionEventRepository events,
            AttentionProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.events = events;
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
        Integer result =
                transactions.execute(status -> events.deleteCreatedBefore(now.minus(properties.eventRetention())));
        int deleted = result == null ? 0 : result;
        log.info("expired attention events cleaned deleted={}", deleted);
        return deleted;
    }
}
