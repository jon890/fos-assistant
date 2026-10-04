package com.bifos.assistant.notification.presentation;

import com.bifos.assistant.notification.application.NotificationPage;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NotificationDtos {

    /**
     * 알림 한 줄이다.
     *
     * @param id 알림의 공개 식별자
     * @param targetType 누르면 갈 곳의 종류. 갈 곳이 없으면 null
     * @param targetId 갈 곳의 공개 식별자. 대화면 대화의 공개 식별자다. 갈 곳이 없으면 null
     * @param readAt 읽음으로 표시한 시각. 읽지 않았으면 null
     */
    public record NotificationView(
            UUID id,
            NotificationKind kind,
            String title,
            String body,
            NotificationTargetType targetType,
            UUID targetId,
            Instant createdAt,
            Instant readAt) {

        public static NotificationView from(Notification notification) {
            return new NotificationView(
                    notification.publicId(),
                    notification.kind(),
                    notification.title(),
                    notification.body(),
                    notification.targetType(),
                    notification.targetPublicId(),
                    notification.createdAt(),
                    notification.readAt());
        }
    }

    /**
     * @param nextCursor 다음 쪽을 읽을 때 {@code cursor} 로 넘긴다. 마지막 쪽이면 null
     * @param unreadCount 그 사용자의 읽지 않은 알림 수
     */
    public record NotificationPageView(List<NotificationView> items, String nextCursor, long unreadCount) {

        public static NotificationPageView from(NotificationPage page) {
            return new NotificationPageView(
                    page.items().stream().map(NotificationView::from).toList(), page.nextCursor(), page.unreadCount());
        }
    }

    public record UnreadCountView(long unreadCount) {}
}
