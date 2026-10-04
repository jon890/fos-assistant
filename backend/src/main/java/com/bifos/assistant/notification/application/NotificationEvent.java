package com.bifos.assistant.notification.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * 사용자 단위 알림 SSE 로 보내는 사건이다(ADR-070). 본문을 싣지 않는다. 화면은 받으면 수를 바꾸고 목록을 다시 읽는다.
 *
 * @param type {@code created} 나 {@code read}
 * @param notificationId 새로 만든 알림의 공개 식별자. {@code read} 사건은 싣지 않는다
 * @param unreadCount 그 사용자의 읽지 않은 알림 수
 */
public record NotificationEvent(
        String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID notificationId,
        long unreadCount) {

    public static final String CREATED = "created";
    public static final String READ = "read";

    /** 새 알림 줄이 커밋됐다. */
    public static NotificationEvent created(UUID notificationId, long unreadCount) {
        return new NotificationEvent(CREATED, notificationId, unreadCount);
    }

    /** 알림 하나나 전부를 읽음으로 표시했다. */
    public static NotificationEvent read(long unreadCount) {
        return new NotificationEvent(READ, null, unreadCount);
    }
}
