package com.bifos.assistant.notification.domain;

import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import java.util.UUID;

/**
 * 알림을 누르면 갈 곳이다. 엔티티에는 두 칸으로 펼쳐 저장한다.
 *
 * @param type 갈 곳의 종류
 * @param publicId 갈 곳의 공개 식별자. 내부 번호는 화면에 내보내지 않는다. 목록 화면을 가리키는
 *     {@link NotificationTargetType#ADMIN_CONNECTIONS} 만 비운다
 */
public record NotificationTarget(NotificationTargetType type, UUID publicId) {

    public NotificationTarget {
        if (type == null || (publicId == null && type != NotificationTargetType.ADMIN_CONNECTIONS)) {
            throw new IllegalArgumentException("notification target needs type, and publicId except for a list");
        }
    }
}
