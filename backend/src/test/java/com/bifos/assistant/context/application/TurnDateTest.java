package com.bifos.assistant.context.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnDateTest {

    @Test
    @DisplayName("그 시각의 한국 날짜와 요일을 한 줄로 적는다")
    void writesSeoulDateAndWeekday() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC);

        assertThat(new TurnDate(clock).line()).isEqualTo("오늘은 2026년 11월 18일 수요일이다(한국 시간).");
    }


    @Test
    @DisplayName("UTC 로는 전날이어도 한국 시간으로 자정을 넘었으면 다음 날을 적는다")
    void usesSeoulDateAcrossMidnight() {
        Clock before = Clock.fixed(Instant.parse("2026-11-18T14:59:59Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(Instant.parse("2026-11-18T15:00:00Z"), ZoneOffset.UTC);

        assertThat(new TurnDate(before).line()).contains("11월 18일 수요일");
        assertThat(new TurnDate(after).line()).contains("11월 19일 목요일");
    }


    @Test
    @DisplayName("지시 끝에 빈 줄을 두고 붙이고 지시가 비었으면 날짜 줄만 돌려준다")
    void appendsAfterBlankLine() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC);

        assertThat(new TurnDate(clock).appendTo("앞 지시")).isEqualTo("앞 지시\n\n" + new TurnDate(clock).line());
        assertThat(new TurnDate(clock).appendTo(null)).isEqualTo(new TurnDate(clock).line());
        assertThat(new TurnDate(clock).appendTo(" ")).isEqualTo(new TurnDate(clock).line());
    }
}
