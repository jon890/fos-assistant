package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HermesRunTerminalsTest {

    private static final HermesRunTerminal COMPLETED = new HermesRunTerminal("run.completed", null, null, true, null, null);


    @Test
    @DisplayName("끝 사건은 한 번 꺼내면 지워지고 profile 이 다르면 꺼내지 못한다")
    void takeRemovesAndIsScopedByProfile() {
        HermesRunTerminals terminals = new HermesRunTerminals();
        terminals.record("profile-a", "run-1", COMPLETED);

        assertThat(terminals.take("profile-b", "run-1")).isEmpty();
        assertThat(terminals.take("profile-a", "run-1")).contains(COMPLETED);
        assertThat(terminals.take("profile-a", "run-1")).isEmpty();
    }


    @Test
    @DisplayName("보존 시간이 지난 끝 사건과 중지 기록은 없는 것으로 본다")
    void expiresOldEntries() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }


            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }


            @Override
            public Instant instant() {
                return now.get();
            }
        };
        HermesRunTerminals terminals = new HermesRunTerminals(clock);
        terminals.record("profile", "run-1", COMPLETED);
        terminals.markStopped("profile", "run-1");
        terminals.markSettled("profile", "run-1");

        now.set(now.get().plus(HermesRunTerminals.RETENTION).plus(Duration.ofSeconds(1)));

        assertThat(terminals.take("profile", "run-1")).isEmpty();
        assertThat(terminals.stopped("profile", "run-1")).isFalse();
        assertThat(terminals.settled("profile", "run-1")).isTrue();

        now.set(now.get().plus(HermesRunTerminals.SETTLED_RETENTION));
        assertThat(terminals.settled("profile", "run-1")).isFalse();
    }
}
