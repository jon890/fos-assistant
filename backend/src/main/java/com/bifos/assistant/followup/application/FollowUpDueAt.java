package com.bifos.assistant.followup.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 할 일의 기한 글을 시각으로 읽는다. 사람이 쓰는 API 와 에이전트의 제안 도구가 함께 쓴다.
 *
 * <p>두 경로 모두 UTC 로 바꾼 연도가 1부터 9999 밖이면 받지 않는다. 그런 시각은 DB 칸과 응답의 ISO-8601 글이 담지 못한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FollowUpDueAt {
    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    /** 시간대가 없을 때 읽는 시간대다. 날짜만 주면 그날 이 시각까지로 본다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);

    /**
     * 사람이 쓰는 API 의 기한이다. {@code 2026-10-05T09:00:00Z} 나 {@code 2026-10-05T18:00:00+09:00} 처럼 시간대가 붙은 시각만
     * 읽는다.
     *
     * @throws ApiException 읽지 못하거나 연도가 범위 밖이면 {@link ErrorCode#VALIDATION_FAILED}
     */
    public static Instant parseWithOffset(String text) {
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dueAt must be an ISO-8601 instant with offset", ex);
        }
        if (!inYearRange(parsed)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dueAt year must be 1 to 9999");
        }
        return parsed;
    }

    /**
     * 제안 도구의 기한이다. 날짜만이면 그날 23시 59분, 시간대가 없으면 {@code Asia/Seoul} 이다.
     *
     * <p>날짜, 시간대가 붙은 시각, 시간대가 없는 시각의 차례로 읽고 처음 읽힌 것을 쓴다. 연도가 범위 밖이면 읽지 못한 것으로 본다.
     *
     * @return 읽지 못하면 비어 있다
     */
    public static Optional<Instant> parseLenient(String text) {
        Instant parsed = null;
        try {
            parsed = LocalDate.parse(text).atTime(END_OF_DAY).atZone(SEOUL).toInstant();
        } catch (DateTimeException ignored) {
            // 날짜만이 아니다. 다음 모양으로 읽는다.
        }
        if (parsed == null) {
            try {
                parsed = OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeException ignored) {
                // 시간대가 붙은 시각이 아니다. 다음 모양으로 읽는다.
            }
        }
        if (parsed == null) {
            try {
                parsed = LocalDateTime.parse(text).atZone(SEOUL).toInstant();
            } catch (DateTimeException ignored) {
                // 세 모양 모두 아니다.
            }
        }
        return parsed == null || !inYearRange(parsed) ? Optional.empty() : Optional.of(parsed);
    }

    private static boolean inYearRange(Instant instant) {
        int year = instant.atZone(ZoneOffset.UTC).getYear();
        return year >= MIN_YEAR && year <= MAX_YEAR;
    }
}
