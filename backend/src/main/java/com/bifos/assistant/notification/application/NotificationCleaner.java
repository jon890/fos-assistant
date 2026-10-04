package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보관 기간이 지난 알림 줄을 지운다(ADR-070).
 *
 * <p>읽었는지는 보지 않는다. 알림은 다른 표의 사실을 알리는 사본이라, 원인이 된 승인 줄과 실행 기록은 따로 남는다.
 */
@Component
@Slf4j
public class NotificationCleaner {

    private final NotificationRepository notifications;
    private final NotificationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. 일정이 이 객체를 직접 부르므로 지우는 쿼리의 트랜잭션을 여기서 연다.
    public NotificationCleaner(
            NotificationRepository notifications,
            NotificationProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.notifications = notifications;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.notification.cleanup-cron}")
    public void runScheduled() {
        cleanExpired(clock.instant());
    }

    /**
     * {@code now} 에서 보관 기간보다 먼저 만든 줄을 지운다.
     *
     * @return 지운 줄 수
     */
    public int cleanExpired(Instant now) {
        Integer result = transactions.execute(
                status -> notifications.deleteByCreatedAtBefore(now.minus(properties.retention())));
        int deleted = result == null ? 0 : result;
        log.info("expired notifications cleaned deleted={}", deleted);
        return deleted;
    }
}
