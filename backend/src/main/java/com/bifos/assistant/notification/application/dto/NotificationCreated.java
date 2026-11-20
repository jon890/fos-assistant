package com.bifos.assistant.notification.application.dto;

import com.bifos.assistant.notification.domain.type.NotificationKind;
import java.time.Instant;
import java.util.UUID;

/**
 * 알림이 저장됐다는 응용 사건이다(ADR-20261120 / web-push). 저장한 트랜잭션이 커밋된 뒤에만 받는 쪽이 돈다.
 *
 * <p>웹 푸시가 이 사건으로 같은 제목과 본문을 기기에 보낸다. 제목과 본문은 로그에 내지 않으므로 {@link #toString()} 이 빼고 적는다.
 *
 * @param notificationId 알림 줄 번호
 * @param userId 받는 사람
 * @param publicId 알림의 공개 식별자. 푸시를 누르면 여는 주소에 쓴다
 */
public record NotificationCreated(
    Long notificationId,
    Long userId,
    NotificationKind kind,
    String title,
    String body,
    UUID publicId,
    Instant createdAt) {

    @Override
    public String toString() {
        return "NotificationCreated[notificationId=" + notificationId + ", userId=" + userId + ", kind=" + kind + "]";
    }
}
