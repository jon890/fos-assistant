package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.domain.type.GrantPeriod;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrantPeriodTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 서울 시각으로 2026-10-01 23:30 이다. UTC 로는 같은 날 14:30 이다. */
    private static final Instant NOW = Instant.parse("2026-10-01T14:30:00Z");

    @Test
    @DisplayName("HOUR 는 한 시간 뒤에 끝난다")
    void hourEndsOneHourLater() {
        assertThat(GrantPeriod.HOUR.expiresAt(NOW, SEOUL)).isEqualTo(Instant.parse("2026-10-01T15:30:00Z"));
    }

    @Test
    @DisplayName("DAYS_30 은 서른 날 뒤 같은 시각에 끝난다")
    void thirtyDaysEndsThirtyDaysLater() {
        assertThat(GrantPeriod.DAYS_30.expiresAt(NOW, SEOUL)).isEqualTo(Instant.parse("2026-10-31T14:30:00Z"));
    }

    @Test
    @DisplayName("TODAY 는 준 시간대의 다음 날 0시에 끝난다")
    void todayEndsAtNextMidnightOfTheGivenZone() {
        // 서울의 다음 날 0시는 UTC 로 같은 날 15:00 이다.
        assertThat(GrantPeriod.TODAY.expiresAt(NOW, SEOUL)).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
        assertThat(GrantPeriod.TODAY.expiresAt(NOW, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    @DisplayName("TODAY 는 그 시간대의 0시 정각에 주어도 다음 날 0시에 끝난다")
    void todayGivenExactlyAtMidnightEndsAtTheNextMidnight() {
        Instant midnight = Instant.parse("2026-10-01T15:00:00Z");

        assertThat(GrantPeriod.TODAY.expiresAt(midnight, SEOUL)).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
    }
}
