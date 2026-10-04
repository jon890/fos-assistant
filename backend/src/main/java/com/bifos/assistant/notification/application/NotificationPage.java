package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.domain.Notification;
import java.util.List;

/**
 * 알림 목록의 한 쪽이다.
 *
 * @param nextCursor 다음 쪽이 시작할 자리. 마지막 쪽이면 null
 * @param unreadCount 그 사용자의 읽지 않은 알림 수. 이 쪽에 든 줄만 세지 않는다
 */
public record NotificationPage(List<Notification> items, String nextCursor, long unreadCount) {}
