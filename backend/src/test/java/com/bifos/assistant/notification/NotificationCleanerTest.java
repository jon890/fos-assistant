package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.application.NotificationCleaner;
import com.bifos.assistant.notification.application.NotificationProperties;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/** 일정이 부르는 {@code runScheduled} 가 주입받은 시계의 시각에서 보관 기간보다 오래된 알림만 지우는지 확인한다. */
@BackendIntegrationTest
class NotificationCleanerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T04:30:00Z");
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final long USER = 970_101L;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        notifications.deleteAll();
    }

    @AfterEach
    void tearDown() {
        notifications.deleteAll();
    }

    @Test
    @DisplayName("보관 기간보다 오래된 줄은 읽었든 읽지 않았든 지우고, 기간 경계의 줄과 그 뒤의 줄은 남긴다")
    void runScheduledDeletesOnlyRowsOlderThanRetention() {
        Instant cutoff = NOW.minus(RETENTION);
        Notification oldUnread = stored(cutoff.minusSeconds(1), false);
        Notification oldRead = stored(cutoff.minus(Duration.ofDays(3)), true);
        Notification atCutoff = stored(cutoff, false);
        Notification fresh = stored(NOW.minus(Duration.ofDays(1)), false);
        NotificationCleaner cleaner = new NotificationCleaner(
                notifications,
                new NotificationProperties(RETENTION, "-", Duration.ofSeconds(20)),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        cleaner.runScheduled();

        assertThat(notifications.findById(oldUnread.id())).as("읽지 않은 오래된 줄").isEmpty();
        assertThat(notifications.findById(oldRead.id())).as("읽은 오래된 줄").isEmpty();
        assertThat(notifications.findById(atCutoff.id())).as("기간 경계의 줄").isPresent();
        assertThat(notifications.findById(fresh.id())).as("기간 안의 줄").isPresent();
    }

    private Notification stored(Instant createdAt, boolean read) {
        Notification notification =
                Notification.of(USER, NotificationKind.APPROVAL_EXPIRED, "승인 요청이 만료됐어요", "", null, createdAt);
        if (read) {
            notification.markRead(createdAt.plusSeconds(60));
        }
        return notifications.save(notification);
    }
}
