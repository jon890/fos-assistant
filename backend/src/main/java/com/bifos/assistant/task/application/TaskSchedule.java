package com.bifos.assistant.task.application;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.scheduling.support.CronExpression;

/**
 * 예약 작업의 시각을 읽고 계산한다(ADR-077, ADR-079).
 *
 * <p>규칙은 {@code docs/backend/task.md} 의 「시각」 이 갖는다. 사람이 쓰는 cron 은 5필드이고 Spring {@link CronExpression} 은
 * 초를 더한 6필드라, 앞에 {@code "0 "} 을 붙여 읽는다. 예정 시각은 그 작업의 시간대로 계산한다. 서머타임에 없는 시각은 그날
 * 건너뛰고 겹친 시각은 서로 다른 두 순간이 된다. 이 계산은 Spring 의 것을 그대로 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskSchedule {

    /** 시각을 펼칠 때 넘지 않는 횟수다. 최소 간격을 지킨 cron 은 1년에 이만큼 돌지 않는다. */
    static final int MAX_STEPS = 400_000;

    /** 최소 간격을 볼 때 펼치는 기간이다. */
    static final Duration HORIZON = Duration.ofDays(365);

    /** 5필드 cron 을 읽는다. 필드가 다섯이 아니거나 읽지 못하면 {@code TASK_SCHEDULE_INVALID} 다. */
    public static CronExpression parseCron(String fiveFields) {
        if (fiveFields == null || fiveFields.isBlank()) {
            throw invalid("a cron expression is required");
        }
        String stripped = fiveFields.strip();
        if (stripped.split("\\s+").length != 5) {
            throw invalid("a cron expression must have five fields");
        }
        try {
            return CronExpression.parse("0 " + stripped);
        } catch (IllegalArgumentException e) {
            throw invalid("this cron expression cannot be read");
        }
    }

    /** IANA 시간대 이름을 읽는다. 읽지 못하면 {@code TASK_SCHEDULE_INVALID} 다. */
    public static ZoneId parseZone(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException e) {
            throw invalid("this time zone is unknown");
        }
    }

    /**
     * {@code from} 부터 1년 안의 예정 시각을 차례로 펼쳐, 이어지는 두 시각의 간격이 {@code min} 보다 짧으면 {@code
     * TASK_SCHEDULE_INVALID} 다.
     */
    public static void requireMinInterval(CronExpression cron, ZoneId zone, Instant from, Duration min) {
        Instant until = from.plus(HORIZON);
        Instant previous = nextAfter(cron, zone, from);
        for (int step = 0; previous != null && !previous.isAfter(until) && step < MAX_STEPS; step++) {
            Instant next = nextAfter(cron, zone, previous);
            if (next == null) {
                return;
            }
            if (Duration.between(previous, next).compareTo(min) < 0) {
                throw invalid("scheduled times must be at least " + min.toMinutes() + " minutes apart");
            }
            previous = next;
        }
    }

    /** {@code after} 뒤의 첫 예정 시각이다. 없으면 null 이다. */
    public static Instant nextAfter(CronExpression cron, ZoneId zone, Instant after) {
        ZonedDateTime next = cron.next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    /** {@code from} 이상 {@code now} 이하의 예정 시각 가운데 가장 늦은 것이다. 없으면 null 이다. */
    public static Instant latestAtOrBefore(CronExpression cron, ZoneId zone, Instant from, Instant now) {
        Instant latest = null;
        Instant next = nextAfter(cron, zone, from.minusNanos(1));
        for (int step = 0; next != null && !next.isAfter(now) && step < MAX_STEPS; step++) {
            latest = next;
            next = nextAfter(cron, zone, next);
        }
        return latest;
    }

    /** 시간대 없는 날짜와 시각을 그 시간대로 해석한다. 지금보다 뒤가 아니면 {@code TASK_SCHEDULE_INVALID} 다. */
    public static Instant onceAt(LocalDateTime fireAt, ZoneId zone, Instant now) {
        Instant at = fireAt.atZone(zone).toInstant();
        if (!at.isAfter(now)) {
            throw invalid("the time must be later than now");
        }
        return at;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, message);
    }
}
