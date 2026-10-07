package com.bifos.assistant.proactive.eval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 평가가 정한 시각만 주는 시계다. 살펴보기 뒤 판단까지 흐르는 시간을 실제로 기다리지 않고 만든다. */
final class EvalClock extends Clock {

    private volatile Instant now;

    EvalClock(Instant now) {
        this.now = now;
    }

    void set(Instant instant) {
        now = instant;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
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
