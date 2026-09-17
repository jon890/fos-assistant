package com.bifos.assistant.usage.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 한 구간의 실행을 하루 단위로 묶은 합계다.
 *
 * <p>날짜는 가족이 사는 곳의 달력으로 끊는다. 질의는 연, 월, 일을 따로 뽑아 주고 여기서 하나의
 * {@link LocalDate} 로 합친다. 데이터베이스마다 날짜 함수의 이름이 달라서, 어느 것에서도 같은 뜻인
 * 세 함수만 쓴다.
 *
 * @param day 묶은 날짜
 */
public record CostByDay(
        LocalDate day,
        Long executions,
        Long estimatedMicros,
        Long actualMicros,
        Long inputTokens,
        Long outputTokens,
        Double avgContextChars,
        Instant firstSeenAt,
        Instant lastSeenAt) {

    public CostByDay(
            Integer year,
            Integer month,
            Integer dayOfMonth,
            Long executions,
            Long estimatedMicros,
            Long actualMicros,
            Long inputTokens,
            Long outputTokens,
            Double avgContextChars,
            Instant firstSeenAt,
            Instant lastSeenAt) {
        this(
                LocalDate.of(year, month, dayOfMonth),
                executions,
                estimatedMicros,
                actualMicros,
                inputTokens,
                outputTokens,
                avgContextChars,
                firstSeenAt,
                lastSeenAt);
    }
}
