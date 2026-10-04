package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 알림 목록에서 다음 쪽이 시작할 자리다. 마지막으로 읽은 줄의 {@code createdAt} 과 {@code id} 다.
 *
 * <p>목록은 {@code createdAt desc, id desc} 로 정렬하므로 이 둘이 있으면 그 줄 바로 다음부터 읽는다. 읽는 사이에 알림이
 * 늘어도 건너뛰거나 겹치지 않는다. 바깥에는 뜻을 알 수 없는 문자열로 내보낸다.
 */
record NotificationCursor(Instant createdAt, long id) {

    private static final char SEPARATOR = '_';

    static NotificationCursor of(Notification last) {
        return new NotificationCursor(last.createdAt(), last.id());
    }

    String encode() {
        String raw = createdAt + String.valueOf(SEPARATOR) + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 읽을 수 없는 값은 {@code VALIDATION_FAILED} 다. */
    static NotificationCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int at = raw.lastIndexOf(SEPARATOR);
            return new NotificationCursor(Instant.parse(raw.substring(0, at)), Long.parseLong(raw.substring(at + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "cursor is not valid");
        }
    }
}
