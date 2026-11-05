package com.bifos.assistant.connector.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 바인딩 줄에 두는 사건 감지 상태의 전이와 저장 모양이다(ADR-20261105 / event-watch). 모든 값은 합성이다. */
class EventWatchStateTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00.123456789Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");


    @Test
    @DisplayName("저장한 JSON 을 다시 읽으면 같은 값이고, 시각은 마이크로초까지 남는다")
    void roundTripsThroughJson() {
        EventWatchState state = EventWatchState.fresh(ZoneId.of("Asia/Seoul"), NOW)
            .checked(NOW, EventWatchResult.WOKE, 2)
            .consumed(NOW, List.of(new EventWatchState.Seen("m1", NOW)), true, TODAY);

        EventWatchState read = EventWatchState.fromJson(state.toJson());

        assertThat(read).isEqualTo(state);
        assertThat(read.checkpoint()).isEqualTo(Instant.parse("2026-11-05T00:00:00.123456Z"));
        assertThat(EventWatchState.fromJson(null)).isNull();
    }


    @Test
    @DisplayName("하루 실행 수는 그날만 세고, 연결 문제의 실패만 연속 실패 수를 늘리며 목록을 받으면 비운다")
    void countsRunsPerDayAndConnectionFailures() {
        EventWatchState woke = EventWatchState.fresh(ZoneId.of("Asia/Seoul"), NOW).consumed(NOW, List.of(), true, TODAY);
        assertThat(woke.runsOn(TODAY)).isEqualTo(1);
        assertThat(woke.runsOn(TODAY.plusDays(1))).isZero();
        assertThat(woke.consumed(NOW, List.of(), true, TODAY.plusDays(1)).runs()).as("날이 바뀌면 처음부터 센다").isEqualTo(1);

        EventWatchState failed = woke.failed(NOW, true).failed(NOW, false);
        assertThat(failed.failures()).isEqualTo(1);
        assertThat(failed.checkpoint()).as("실패는 지점을 옮기지 않는다").isEqualTo(woke.checkpoint());
        assertThat(failed.reachable().failures()).isZero();
    }


    @Test
    @DisplayName("조회 시작은 지점보다 겹침만큼 앞이되 켠 시각보다 앞서지 않는다")
    void sinceNeverPrecedesEnabling() {
        EventWatchState fresh = EventWatchState.fresh(ZoneId.of("Asia/Seoul"), NOW);
        assertThat(fresh.since(Duration.ofMinutes(30))).isEqualTo(fresh.enabledAt());

        Instant later = NOW.plus(Duration.ofHours(2));
        EventWatchState moved = fresh.consumed(later, List.of(), false, TODAY);
        assertThat(moved.since(Duration.ofMinutes(30))).isEqualTo(moved.checkpoint().minus(Duration.ofMinutes(30)));
    }


    @Test
    @DisplayName("미룬 알림은 쌓이고 비울 수 있으며, 다시 연결 알림 표시는 목록을 받으면 풀린다. 두 칸이 없는 옛 JSON 도 읽는다")
    void defersNoticesAndReadsOldJson() {
        EventWatchState fresh = EventWatchState.fresh(ZoneId.of("Asia/Seoul"), NOW);
        EventWatchState deferred = fresh.defer(3, 1, 7L, "메모", "메모").defer(2, 0, 8L, "메모함", "메모");
        assertThat(deferred.deferred()).isEqualTo(new EventWatchState.Deferred(2, 5, 1, 8L, "메모함", "메모"));
        assertThat(deferred.withoutDeferred().deferred()).isNull();

        EventWatchState noticed = fresh.failed(NOW, true).failureNoticed();
        assertThat(noticed.failureNotified()).isTrue();
        assertThat(noticed.failed(NOW, true).failureNotified()).as("실패가 이어지는 동안 표시가 남는다").isTrue();
        assertThat(noticed.reachable().failureNotified()).isFalse();

        String old = "{\"timeZone\":\"Asia/Seoul\",\"checkpoint\":\"2026-11-05T00:00:00Z\",\"seen\":[],\"failures\":2,"
            + "\"runs\":0,\"lastNewCount\":0}";
        EventWatchState read = EventWatchState.fromJson(old);
        assertThat(read.failureNotified()).isFalse();
        assertThat(read.deferred()).isNull();
        assertThat(read.failures()).isEqualTo(2);
    }
}
