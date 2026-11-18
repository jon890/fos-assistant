package com.bifos.assistant.context.application;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대화 실행의 turn 전용 지시에 붙이는 오늘 날짜 한 줄이다.
 *
 * <p>공통 답변 지침에 넣지 않는다. 넣으면 {@code instructions_hash} 가 날마다 바뀌어 지문으로 지침 변경을 찾지 못한다. Hermes 가 넣는 날짜는
 * 문맥을 마지막으로 다시 만든 때의 것이라 turn 마다 갱신되지 않는다({@code hermes/docs/hermes-contract.md} 의 「웹 검색 서비스」).
 */
@Component
@RequiredArgsConstructor
public class TurnDate {
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN);

    private final Clock clock;


    /** 지금의 한국 날짜와 요일을 적은 한 줄이다. */
    public String line() {
        ZonedDateTime now = clock.instant().atZone(HOUSEHOLD_ZONE);
        return "오늘은 " + DATE.format(now) + "이다(한국 시간).";
    }


    /** 지시 끝에 날짜 줄을 붙인다. 지시가 비어 있으면 날짜 줄만 돌려준다. */
    public String appendTo(String instructions) {
        String line = line();
        return instructions == null || instructions.isBlank() ? line : instructions + "\n\n" + line;
    }
}
