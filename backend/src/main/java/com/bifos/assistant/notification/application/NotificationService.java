package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림을 만들고 읽고 읽음으로 표시한다(ADR-070).
 *
 * <p>계약은 {@code docs/backend/notification.md} 가 갖는다. 알림과 그 원인은 한 트랜잭션이다. 줄은 원인을 저장하는
 * 트랜잭션 안에서 만들고, 사건은 그 트랜잭션이 커밋한 뒤에 그 사용자의 구독자에게 보낸다. 화면이 사건을 받고 다시 읽을 때
 * 줄이 있어야 한다.
 *
 * <p>사건에 싣는 읽지 않은 수는 커밋한 뒤 새 트랜잭션에서 센다. 바꾼 트랜잭션 안에서 세면 동시에 커밋한 다른 트랜잭션의
 * 알림이 빠진 수가 나중에 도착해 화면의 수를 덮는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 목록 한 쪽의 상한이다. */
    public static final int MAX_PAGE = 100;

    private static final String NOT_FOUND_MESSAGE = "this notification does not exist";

    /** 커밋한 뒤의 콜백 안에서 끝난 트랜잭션에 참여하지 않도록 늘 새 읽기 트랜잭션을 연다. */
    private static final TransactionDefinition NEW_READ = newRead();

    private final NotificationRepository notifications;
    private final NotificationEventHub hub;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /**
     * 알림을 저장한다. 부르는 쪽의 트랜잭션에 참여하고, 트랜잭션 밖에서 부르면 실패한다.
     *
     * <p>저장이 실패하면 예외가 그대로 나가 원인의 저장도 되돌아간다. 사건은 커밋한 뒤에 보낸다.
     *
     * @param body 사람이 읽는 짧은 본문. 도구 인자 원문, 비밀값, 모델 답 전문을 넣지 않는다
     * @param target 누르면 갈 곳. 없으면 null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification notify(
            Long userId, NotificationKind kind, String title, String body, NotificationTarget target) {
        Notification saved =
                notifications.saveAndFlush(Notification.of(userId, kind, title, body, target, clock.instant()));
        afterCommit(userId, unread -> NotificationEvent.created(saved.publicId(), unread));
        return saved;
    }

    /**
     * 내 알림을 최근 것부터 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽이 돌려준 다음 자리. 첫 쪽이면 null
     * @param limit 1 이상 {@link #MAX_PAGE} 이하. 범위 밖이면 {@code VALIDATION_FAILED}
     */
    @Transactional(readOnly = true)
    public NotificationPage page(CurrentUser user, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_PAGE);
        }
        // 한 줄을 더 읽어 다음 쪽이 있는지 안다.
        PageRequest window = PageRequest.ofSize(limit + 1);
        List<Notification> rows;
        if (cursor == null) {
            rows = notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), window);
        } else {
            NotificationCursor from = NotificationCursor.decode(cursor);
            rows = notifications.findPageAfter(user.id(), from.createdAt(), from.id(), window);
        }
        long unread = notifications.countByUserIdAndReadAtIsNull(user.id());
        if (rows.size() <= limit) {
            return new NotificationPage(rows, null, unread);
        }
        List<Notification> items = rows.subList(0, limit);
        return new NotificationPage(
                items, NotificationCursor.of(items.getLast()).encode(), unread);
    }

    /**
     * 내 알림 하나를 읽음으로 표시한다. 이미 읽은 줄은 바꾸지 않고 그대로 돌려준다.
     *
     * <p>없는 줄과 남의 줄은 같은 {@code NOTIFICATION_NOT_FOUND} 다. 새로 읽음이 되면 커밋한 뒤 {@code read} 사건을 보낸다.
     */
    @Transactional
    public Notification markRead(CurrentUser user, UUID notificationId) {
        Notification notification = notifications
                .findByPublicIdAndUserId(notificationId, user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND, NOT_FOUND_MESSAGE));
        if (!notification.unread()) {
            return notification;
        }
        notification.markRead(clock.instant());
        Notification saved = notifications.saveAndFlush(notification);
        afterCommit(user.id(), NotificationEvent::read);
        return saved;
    }

    /**
     * 내 읽지 않은 알림을 모두 읽음으로 표시하고 커밋한 뒤 {@code read} 사건을 보낸다.
     *
     * @return 이 트랜잭션이 본 남은 읽지 않은 수. 늘 0 이다. 사건에는 커밋한 뒤 새로 센 수를 싣는다
     */
    @Transactional
    public long markAllRead(CurrentUser user) {
        notifications.markAllRead(user.id(), clock.instant());
        afterCommit(user.id(), NotificationEvent::read);
        return 0;
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * 트랜잭션이 커밋한 뒤에 그 사용자의 읽지 않은 수를 새로 세어 사건을 만들고 보낸다. 되돌아가면 보내지 않는다.
     *
     * <p>세거나 보내다 난 예외는 로그만 남긴다. 줄은 이미 커밋됐고, 예외가 나가면 원인의 처리가 실패한 것처럼 보인다.
     *
     * @param event 새로 센 읽지 않은 수로 보낼 사건을 만든다
     */
    private void afterCommit(Long userId, LongFunction<NotificationEvent> event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Long unread = new TransactionTemplate(transactionManager, NEW_READ)
                            .execute(status -> notifications.countByUserIdAndReadAtIsNull(userId));
                    hub.publish(userId, event.apply(unread == null ? 0 : unread));
                } catch (RuntimeException ex) {
                    log.warn("알림 사건을 보내지 못했다 userId={}", userId, ex);
                }
            }
        });
    }

    private static TransactionDefinition newRead() {
        DefaultTransactionDefinition definition =
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setReadOnly(true);
        return definition;
    }
}
