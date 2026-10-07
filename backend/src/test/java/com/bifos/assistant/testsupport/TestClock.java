package com.bifos.assistant.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 검사가 시각을 정하고 옮길 수 있는 시계다.
 *
 * <p>정하지 않으면 실제 UTC 시각을 준다. {@link IntegrationTestIsolation} 이 검사마다 {@link #reset()} 으로 실제 시각으로 되돌린다.
 */
public final class TestClock extends Clock {
    private volatile Instant fixed;

    /** 이 시각에 멈춘다. */
    public void set(Instant now) {
        fixed = now;
    }

    /** 멈춘 시각을 옮긴다. 멈춰 있지 않으면 지금 시각에서 옮긴 뒤 멈춘다. */
    public synchronized void advance(Duration duration) {
        fixed = instant().plus(duration);
    }

    /** 실제 시각으로 되돌린다. */
    public void reset() {
        fixed = null;
    }

    @Override
    public Instant instant() {
        Instant now = fixed;
        return now != null ? now : Instant.now();
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
