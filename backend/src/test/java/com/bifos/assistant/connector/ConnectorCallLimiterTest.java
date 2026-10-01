package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.application.ConnectorProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자별 동시 1개와 60초 10회 제한을 시각을 고정해 본다. */
class ConnectorCallLimiterTest {
    private static final Long USER = 1L;
    private static final Long OTHER = 2L;

    private final SteppingClock clock = new SteppingClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final ConnectorCallLimiter limiter = new ConnectorCallLimiter(new ConnectorProperties(1, 10), clock);

    @Test
    @DisplayName("같은 사용자의 둘째 동시 호출은 action 을 부르지 않고 거절하고 다른 사용자는 받는다")
    void rejectsSecondConcurrentCallOfSameUser() {
        AtomicInteger secondRuns = new AtomicInteger();

        String result = limiter.call(USER, () -> {
            assertRateLimited(() -> limiter.call(USER, secondRuns::incrementAndGet));
            return limiter.call(OTHER, () -> "other accepted");
        });

        assertThat(result).isEqualTo("other accepted");
        assertThat(secondRuns).hasValue(0);
    }

    @Test
    @DisplayName("action 이 예외를 내도 동시 자리를 돌려줘 다음 호출을 받는다")
    void releasesSlotWhenActionThrows() {
        assertThatThrownBy(() -> limiter.call(USER, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(limiter.call(USER, () -> "next")).isEqualTo("next");
    }

    @Test
    @DisplayName("60초 안의 열 번은 받고 열한 번째는 거절하며 61초 뒤에는 다시 받는다")
    void rejectsEleventhCallWithinAMinute() {
        AtomicInteger runs = new AtomicInteger();
        for (int call = 1; call <= 10; call++) {
            limiter.call(USER, runs::incrementAndGet);
        }
        assertThat(runs).hasValue(10);

        // 창의 끝인 60초까지는 처음 시각이 남아 있다.
        clock.advance(Duration.ofSeconds(60));
        assertRateLimited(() -> limiter.call(USER, runs::incrementAndGet));
        assertThat(runs).hasValue(10);
        assertThat(limiter.call(OTHER, () -> "other accepted")).isEqualTo("other accepted");

        clock.advance(Duration.ofSeconds(1));
        limiter.call(USER, runs::incrementAndGet);
        assertThat(runs).hasValue(11);
    }

    @Test
    @DisplayName("거절된 호출은 횟수에 들지 않아 계속 눌러도 창이 지나면 풀린다")
    void rejectedCallsAreNotCounted() {
        AtomicInteger runs = new AtomicInteger();
        for (int call = 1; call <= 10; call++) {
            limiter.call(USER, runs::incrementAndGet);
        }
        // 거절되는 호출을 30초 뒤에 스무 번 넣는다. 횟수에 들면 처음 열 번이 빠진 뒤에도 한도가 차 있다.
        clock.advance(Duration.ofSeconds(30));
        for (int call = 1; call <= 20; call++) {
            assertRateLimited(() -> limiter.call(USER, runs::incrementAndGet));
        }

        clock.advance(Duration.ofSeconds(31));
        for (int call = 1; call <= 10; call++) {
            limiter.call(USER, runs::incrementAndGet);
        }

        assertThat(runs).hasValue(20);
    }

    private static void assertRateLimited(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_RATE_LIMITED));
    }

    /** 검사가 시각을 앞으로 옮기는 Clock 이다. */
    private static final class SteppingClock extends Clock {
        private Instant now;

        private SteppingClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
