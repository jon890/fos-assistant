package com.bifos.assistant.usage.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolTraceLedgerTest {
    private static final ToolTrace TRACE = new ToolTrace("terminal", 1_200L, "tool_error", "명령을 찾지 못했다");
    private final MovingClock clock = new MovingClock();
    private final ToolTraceLedger ledger = new ToolTraceLedger(clock);


    @Test
    @DisplayName("기록이 완료 줄보다 먼저 오면 완료 줄을 만들 때 꺼낸다")
    void traceBeforeEventIsClaimed() {
        assertThat(ledger.offer(1L, "call_1", TRACE)).isNull();

        assertThat(ledger.claim(1L, "call_1")).isEqualTo(TRACE);
        assertThat(ledger.saved(1L, "call_1", 10L)).isNull();
    }


    @Test
    @DisplayName("기록이 만들기와 저장 사이에 오면 저장 직후 알림이 꺼낸다")
    void traceBetweenBuildAndSaveIsReturnedOnSave() {
        assertThat(ledger.claim(1L, "call_1")).isNull();
        assertThat(ledger.offer(1L, "call_1", TRACE)).isNull();

        assertThat(ledger.saved(1L, "call_1", 10L)).isEqualTo(TRACE);
    }


    @Test
    @DisplayName("기록이 저장 뒤에 오면 그 줄의 사건 번호를 돌려준다")
    void traceAfterSaveGetsEventId() {
        ledger.claim(1L, "call_1");
        ledger.saved(1L, "call_1", 10L);

        assertThat(ledger.offer(1L, "call_1", TRACE)).isEqualTo(10L);
        assertThat(ledger.offer(2L, "call_1", TRACE)).as("다른 실행의 같은 번호는 다른 열쇠다").isNull();
    }


    @Test
    @DisplayName("같은 실행에서 호출 번호가 다시 쓰이면 그 번호의 기록을 버린다")
    void reusedCallIdIsDropped() {
        ledger.saved(1L, "call_0", 10L);
        assertThat(ledger.saved(1L, "call_0", 11L)).isNull();

        assertThat(ledger.offer(1L, "call_0", TRACE)).isNull();
        assertThat(ledger.claim(1L, "call_0")).isNull();

        assertThat(ledger.offer(1L, "call_9", TRACE)).isNull();
        assertThat(ledger.offer(1L, "call_9", TRACE)).as("기록이 두 번 오면 어느 줄인지 모른다").isNull();
        assertThat(ledger.claim(1L, "call_9")).isNull();
    }


    @Test
    @DisplayName("10분이 지난 항목은 버린다")
    void expiresOldEntries() {
        ledger.offer(1L, "call_1", TRACE);
        ledger.saved(1L, "call_2", 20L);

        clock.advanceMinutes(11);

        assertThat(ledger.claim(1L, "call_1")).isNull();
        assertThat(ledger.offer(1L, "call_2", TRACE)).isNull();
    }


    @Test
    @DisplayName("표가 상한을 넘으면 오래된 항목부터 버린다")
    void dropsOldestOverLimit() {
        for (int i = 0; i <= ToolTraceLedger.LIMIT; i++) {
            ledger.offer(1L, "call_" + i, TRACE);
        }

        assertThat(ledger.claim(1L, "call_0")).isNull();
        assertThat(ledger.claim(1L, "call_" + ToolTraceLedger.LIMIT)).isEqualTo(TRACE);
    }


    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T00:00:00Z");


        void advanceMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }


        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }


        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }


        @Override
        public Instant instant() {
            return now;
        }
    }
}
