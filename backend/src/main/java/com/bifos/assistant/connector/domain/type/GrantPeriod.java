package com.bifos.assistant.connector.domain.type;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 승인하면서 그 도구에 주는 상시 허락의 기간이다(ADR-050). 무기한은 없다.
 *
 * <p>요청 본문의 글자 그대로다. 값을 바꾸면 화면도 함께 바꾼다.
 */
public enum GrantPeriod {
    HOUR,
    TODAY,
    DAYS_30;

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    /**
     * {@code now} 에 준 허락이 끝나는 시각이다.
     *
     * @param zone {@code TODAY} 가 그날의 끝을 정하는 시간대. 그 시간대의 다음 날 0시에 끝난다
     */
    public Instant expiresAt(Instant now, ZoneId zone) {
        return switch (this) {
            case HOUR -> now.plus(ONE_HOUR);
            case TODAY ->
                now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
            case DAYS_30 -> now.plus(THIRTY_DAYS);
        };
    }
}
