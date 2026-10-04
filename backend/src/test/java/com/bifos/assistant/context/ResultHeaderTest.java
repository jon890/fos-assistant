package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결과 항목의 신선도 판정과 출처 머리줄의 모양을 고정 시각으로 본다(ADR-071). */
class ResultHeaderTest {

    private static final Duration SIX_HOURS = Duration.ofHours(6);

    /** 2026-10-03 17:30 Asia/Seoul 이다. */
    private static final Instant NOW = Instant.parse("2026-10-03T08:30:00Z");

    @Test
    @DisplayName("끝난 시각이 비어 있으면 신선도는 UNKNOWN 이다")
    void returnsUnknownWhenAsOfIsMissing() {
        assertThat(ResultHeader.freshnessOf(null, NOW, SIX_HOURS)).isEqualTo(ContextFreshness.UNKNOWN);
    }

    @Test
    @DisplayName("끝난 지 정확히 6시간이면 아직 FRESH 이고 1초라도 넘으면 STALE 이다")
    void becomesStaleOnlyAfterStaleAfterPasses() {
        assertThat(ResultHeader.freshnessOf(NOW.minus(SIX_HOURS), NOW, SIX_HOURS))
                .as("정확히 6시간 전")
                .isEqualTo(ContextFreshness.FRESH);
        assertThat(ResultHeader.freshnessOf(NOW.minus(SIX_HOURS).minusSeconds(1), NOW, SIX_HOURS))
                .as("6시간 1초 전")
                .isEqualTo(ContextFreshness.STALE);
        assertThat(ResultHeader.freshnessOf(NOW.minus(Duration.ofMinutes(10)), NOW, SIX_HOURS))
                .as("10분 전")
                .isEqualTo(ContextFreshness.FRESH);
    }

    @Test
    @DisplayName("FRESH 머리줄은 출처와 칸과 Asia/Seoul 의 끝난 시각을 한 줄에 적고 오래됨을 붙이지 않는다")
    void rendersFreshHeaderInSeoulTimeWithoutStaleNote() {
        Instant finished = Instant.parse("2026-10-03T05:05:00Z");

        String header = ResultHeader.render(
                "맡긴 일",
                List.of("에이전트: 조사원", "실행 번호: 412", "상태: SUCCEEDED"),
                finished,
                ContextFreshness.FRESH,
                SIX_HOURS);

        assertThat(header).isEqualTo("[출처: 맡긴 일, 에이전트: 조사원, 실행 번호: 412, 상태: SUCCEEDED, 끝난 시각: 2026-10-03 14:05]");
    }

    @Test
    @DisplayName("STALE 머리줄은 신선도를 괄호 안에 넣고 다음 줄에 기준 시간 수를 담은 안내를 붙인다")
    void rendersStaleHeaderWithNoteLine() {
        Instant executed = Instant.parse("2026-10-02T23:07:00Z");

        String header = ResultHeader.render(
                "승인한 동작",
                List.of("동작: 초안 만들기", "상태: SUCCEEDED"),
                executed,
                ResultHeader.freshnessOf(executed, NOW, SIX_HOURS),
                SIX_HOURS);

        assertThat(header)
                .isEqualTo("[출처: 승인한 동작, 동작: 초안 만들기, 상태: SUCCEEDED, 끝난 시각: 2026-10-03 08:07, 신선도: 오래됨]\n"
                        + "이 결과는 6시간보다 전에 끝났다. 지금 상태와 다를 수 있다.");
    }

    @Test
    @DisplayName("끝난 시각이 비어 있으면 머리줄에 끝난 시각: 모름 을 적는다")
    void rendersUnknownTimeWhenAsOfIsMissing() {
        String header = ResultHeader.render(
                "승인한 동작",
                List.of("동작: 초안 만들기", "상태: UNKNOWN"),
                null,
                ResultHeader.freshnessOf(null, NOW, SIX_HOURS),
                SIX_HOURS);

        assertThat(header).isEqualTo("[출처: 승인한 동작, 동작: 초안 만들기, 상태: UNKNOWN, 끝난 시각: 모름]");
    }
}
