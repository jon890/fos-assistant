package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.notification.application.NotificationEvent;
import com.bifos.assistant.notification.application.NotificationEventHub;
import com.bifos.assistant.notification.application.NotificationPage;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림을 만들고 읽고 읽음으로 표시하는 흐름을 실제 DB 로 확인한다(ADR-070).
 *
 * <p>계약은 {@code docs/backend/notification.md} 다. 받는 사람은 번호로만 두므로 사용자 줄을 만들지 않고 다른 검사와 겹치지
 * 않는 번호를 쓴다.
 */
@BackendIntegrationTest
class NotificationServiceTest {

    private static final long ALICE = 970_001L;
    private static final long BOB = 970_002L;
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    NotificationService service;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    NotificationEventHub hub;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<NotificationEvent> aliceEvents = new CopyOnWriteArrayList<>();
    private final List<NotificationEvent> bobEvents = new CopyOnWriteArrayList<>();
    private final List<Runnable> subscriptions = new ArrayList<>();
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        notifications.deleteAll();
        transactions = new TransactionTemplate(transactionManager);
        subscriptions.add(hub.subscribe(ALICE, aliceEvents::add));
        subscriptions.add(hub.subscribe(BOB, bobEvents::add));
    }

    @AfterEach
    void tearDown() {
        subscriptions.forEach(Runnable::run);
        notifications.deleteAll();
    }

    @Test
    @DisplayName("알림을 저장하면 커밋한 뒤에 그 사용자의 구독자만 created 사건과 읽지 않은 수를 받는다")
    void notifyPublishesCreatedToOwnerOnlyAfterCommit() {
        List<NotificationEvent> seenBeforeCommit = new ArrayList<>();
        Notification saved = transactions.execute(status -> {
            Notification row = service.notify(
                    ALICE,
                    NotificationKind.APPROVAL_REQUESTED,
                    "승인을 기다리는 요청이 있어요",
                    "「메모 쓰기」",
                    new NotificationTarget(NotificationTargetType.CONVERSATION, UUID.randomUUID()));
            seenBeforeCommit.addAll(aliceEvents);
            return row;
        });

        assertThat(seenBeforeCommit).as("커밋 전에 받은 사건").isEmpty();
        assertThat(aliceEvents).containsExactly(NotificationEvent.created(saved.publicId(), 1));
        assertThat(bobEvents).as("다른 사용자가 받은 사건").isEmpty();
        Notification stored = notifications.findAll().getFirst();
        assertThat(stored.userId()).isEqualTo(ALICE);
        assertThat(stored.kind()).isEqualTo(NotificationKind.APPROVAL_REQUESTED);
        assertThat(stored.readAt()).isNull();
    }

    @Test
    @DisplayName("두 트랜잭션이 겹쳐 차례로 커밋하면 나중에 커밋한 쪽의 사건이 두 알림을 모두 센 2 를 싣는다")
    void laterCommitCountsBothWhenTransactionsOverlap() {
        Notification first = transactions.execute(status -> {
            Notification row = service.notify(ALICE, NotificationKind.APPROVAL_REQUESTED, "먼저 연 쪽", "", null);
            // 먼저 연 트랜잭션이 커밋하기 전에 다른 스레드의 트랜잭션이 알림을 만들고 먼저 커밋한다.
            CompletableFuture.runAsync(() -> transactions.executeWithoutResult(
                            inner -> service.notify(ALICE, NotificationKind.APPROVAL_REQUESTED, "나중에 연 쪽", "", null)))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            return row;
        });

        assertThat(aliceEvents).hasSize(2);
        assertThat(aliceEvents.getLast()).as("나중에 커밋한 쪽의 사건").isEqualTo(NotificationEvent.created(first.publicId(), 2));
        assertThat(service.unreadCount(ALICE)).isEqualTo(2);
    }

    @Test
    @DisplayName("트랜잭션이 되돌아가면 알림도 사건도 남지 않는다")
    void rolledBackTransactionLeavesNoRowAndNoEvent() {
        transactions.executeWithoutResult(status -> {
            service.notify(ALICE, NotificationKind.APPROVAL_EXPIRED, "승인 요청이 만료됐어요", "", null);
            status.setRollbackOnly();
        });

        assertThat(notifications.findAll()).isEmpty();
        assertThat(aliceEvents).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 알림을 만들면 실패하고 줄을 남기지 않는다")
    void notifyOutsideTransactionFails() {
        assertThatThrownBy(() -> service.notify(ALICE, NotificationKind.APPROVAL_EXPIRED, "제목", "", null))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("201자 제목과 501자 본문은 칸 길이인 200자와 500자로 잘려 저장된다")
    void clipsTitleAndBodyToColumnLength() {
        transactions.executeWithoutResult(status ->
                service.notify(ALICE, NotificationKind.APPROVAL_REQUESTED, "가".repeat(201), "나".repeat(501), null));

        Notification stored = notifications.findAll().getFirst();
        assertThat(stored.title()).isEqualTo("가".repeat(200));
        assertThat(stored.body()).isEqualTo("나".repeat(500));
        assertThat(stored.targetType()).isNull();
        assertThat(stored.targetPublicId()).isNull();
    }

    @Test
    @DisplayName("목록은 만든 시각의 역순이고 같은 시각이면 번호가 큰 쪽이 앞이며 다음 쪽이 겹치지 않고 이어진다")
    void pagesNewestFirstAndContinuesWithCursor() {
        Notification oldest = stored(ALICE, BASE);
        Notification sameTimeFirst = stored(ALICE, BASE.plusSeconds(10));
        Notification sameTimeSecond = stored(ALICE, BASE.plusSeconds(10));
        Notification newest = stored(ALICE, BASE.plusSeconds(20));
        stored(BOB, BASE.plusSeconds(30));

        NotificationPage first = service.page(alice(), null, 2);
        NotificationPage second = service.page(alice(), first.nextCursor(), 2);

        assertThat(first.items()).extracting(Notification::id).containsExactly(newest.id(), sameTimeSecond.id());
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.unreadCount()).isEqualTo(4);
        assertThat(second.items()).extracting(Notification::id).containsExactly(sameTimeFirst.id(), oldest.id());
        assertThat(second.nextCursor()).as("마지막 쪽의 다음 자리").isNull();
    }

    @Test
    @DisplayName("형식이 틀린 커서는 VALIDATION_FAILED 다")
    void rejectsMalformedCursor() {
        assertCode(() -> service.page(alice(), "not-a-cursor", 10), ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    @DisplayName("limit 이 1 보다 작거나 100 보다 크면 VALIDATION_FAILED 다")
    void rejectsLimitOutsideRange(int limit) {
        assertCode(() -> service.page(alice(), null, limit), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("limit 100 은 받는다")
    void acceptsLimitAtUpperBound() {
        stored(ALICE, BASE);

        assertThat(service.page(alice(), null, 100).items()).hasSize(1);
    }

    @Test
    @DisplayName("남의 알림이나 없는 알림을 읽음으로 표시하면 NOTIFICATION_NOT_FOUND 이고 줄은 그대로다")
    void markReadOfOthersNotificationIsNotFound() {
        Notification bobs = stored(BOB, BASE);

        assertCode(() -> service.markRead(alice(), bobs.publicId()), ErrorCode.NOTIFICATION_NOT_FOUND);
        assertCode(() -> service.markRead(alice(), UUID.randomUUID()), ErrorCode.NOTIFICATION_NOT_FOUND);
        assertThat(notifications.findById(bobs.id()).orElseThrow().readAt()).isNull();
        assertThat(bobEvents).isEmpty();
    }

    @Test
    @DisplayName("읽음으로 표시하면 read 사건과 남은 수를 보내고, 이미 읽은 줄을 다시 읽어도 read_at 이 바뀌지 않는다")
    void markReadKeepsFirstReadTime() {
        Notification first = stored(ALICE, BASE);
        stored(ALICE, BASE.plusSeconds(1));

        Instant readAt = service.markRead(alice(), first.publicId()).readAt();
        Notification again = service.markRead(alice(), first.publicId());

        assertThat(readAt).isNotNull();
        assertThat(again.readAt()).isEqualTo(readAt);
        assertThat(notifications.findById(first.id()).orElseThrow().readAt()).isEqualTo(readAt);
        assertThat(aliceEvents).as("새로 읽음이 된 한 번만 사건이 간다").containsExactly(NotificationEvent.read(1));
    }

    @Test
    @DisplayName("시계가 나노초까지 주어도 만든 시각과 읽은 시각은 DB 에서 다시 읽은 값과 같다")
    void storedTimesMatchDatabasePrecisionWhenClockHasNanos() {
        // Linux 의 시스템 시계는 나노초까지 주지만 칸은 DATETIME(6) 이다. 그 시계를 고정해 macOS 에서도 재현한다.
        Instant nanos = Instant.parse("2026-10-01T00:00:00.123456789Z");
        NotificationService nanoService =
                new NotificationService(notifications, hub, Clock.fixed(nanos, ZoneOffset.UTC), transactionManager);
        Instant micros = Instant.parse("2026-10-01T00:00:00.123456Z");

        Notification created = transactions.execute(
                status -> nanoService.notify(ALICE, NotificationKind.APPROVAL_REQUESTED, "제목", "본문", null));
        Instant readAt = transactions
                .execute(status -> nanoService.markRead(alice(), created.publicId()))
                .readAt();
        Notification again = transactions.execute(status -> nanoService.markRead(alice(), created.publicId()));

        assertThat(created.createdAt()).as("만든 시각").isEqualTo(micros);
        assertThat(readAt).as("처음 읽은 시각").isEqualTo(micros);
        assertThat(again.readAt()).as("다시 읽음으로 표시한 뒤 DB 에서 읽은 시각").isEqualTo(readAt);
        Notification stored = notifications.findById(created.id()).orElseThrow();
        assertThat(stored.createdAt()).as("DB 의 만든 시각").isEqualTo(created.createdAt());
        assertThat(stored.readAt()).as("DB 의 읽은 시각").isEqualTo(readAt);
    }

    @Test
    @DisplayName("모두 읽음 뒤 읽지 않은 수가 0 이고 남의 알림은 그대로다")
    void markAllReadLeavesNoUnread() {
        stored(ALICE, BASE);
        stored(ALICE, BASE.plusSeconds(1));
        Notification bobs = stored(BOB, BASE);

        long remaining = service.markAllRead(alice());

        assertThat(remaining).isZero();
        assertThat(service.unreadCount(ALICE)).isZero();
        assertThat(service.page(alice(), null, 10).items())
                .allSatisfy(row -> assertThat(row.readAt()).isNotNull());
        assertThat(service.unreadCount(BOB)).isEqualTo(1);
        assertThat(notifications.findById(bobs.id()).orElseThrow().readAt()).isNull();
        assertThat(aliceEvents).containsExactly(NotificationEvent.read(0));
        assertThat(bobEvents).isEmpty();
    }

    /** 만든 시각을 정해 읽지 않은 줄 하나를 넣는다. */
    private Notification stored(long userId, Instant createdAt) {
        return notifications.save(
                Notification.of(userId, NotificationKind.APPROVAL_REQUESTED, "제목", "본문", null, createdAt));
    }

    private static CurrentUser alice() {
        return new CurrentUser(ALICE, "alice@example.com", "앨리스", 1L, UserRole.MEMBER);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).as("오류 코드").isEqualTo(expected));
    }
}
