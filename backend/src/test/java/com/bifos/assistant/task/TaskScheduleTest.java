package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.TaskSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.support.CronExpression;

/** 예약 작업의 시각 계산을 본다. 규칙은 {@code docs/backend/task.md} 의 「시각」 이다. */
class TaskScheduleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Duration MIN = Duration.ofMinutes(15);
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    @DisplayName("매달 1일 9시는 서울 시간으로 계산해 다음 달 1일 0시 UTC 다")
    void computesNextMonthlyRunInSeoul() {
        CronExpression cron = TaskSchedule.parseCron("0 9 1 * *");

        assertThat(TaskSchedule.nextAfter(cron, SEOUL, NOW)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("2월 31일처럼 오지 않는 날은 다음 시각이 없다")
    void returnsNoNextTimeForImpossibleDate() {
        CronExpression cron = TaskSchedule.parseCron("0 9 31 2 *");

        assertThat(TaskSchedule.nextAfter(cron, SEOUL, NOW)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 9 * *", "0 0 9 * * *", "61 9 * * *", "abc", "", "   "})
    @DisplayName("필드가 다섯이 아니거나 읽지 못하는 cron 은 TASK_SCHEDULE_INVALID 다")
    void rejectsCronWithWrongFieldCountOrUnreadable(String cron) {
        assertInvalid(() -> TaskSchedule.parseCron(cron));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/5 * * * *", "0,10 9 * * *"})
    @DisplayName("이어지는 두 시각이 15분보다 가까운 cron 은 TASK_SCHEDULE_INVALID 다")
    void rejectsCronShorterThanMinInterval(String expression) {
        CronExpression cron = TaskSchedule.parseCron(expression);

        assertInvalid(() -> TaskSchedule.requireMinInterval(cron, SEOUL, NOW, MIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/15 * * * *", "0 9 * * 1"})
    @DisplayName("이어지는 두 시각이 15분 이상인 cron 은 통과한다")
    void acceptsCronAtLeastMinInterval(String expression) {
        CronExpression cron = TaskSchedule.parseCron(expression);

        assertThatCode(() -> TaskSchedule.requireMinInterval(cron, SEOUL, NOW, MIN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사흘 놓친 매일 9시 작업은 마지막 하루의 시각 하나만 돌려준다")
    void returnsOnlyLatestOfThreeMissedDailyRuns() {
        CronExpression cron = TaskSchedule.parseCron("0 9 * * *");
        Instant from = Instant.parse("2026-10-01T00:00:00Z"); // 서울 10월 1일 9시
        Instant now = Instant.parse("2026-10-03T03:00:00Z"); // 서울 10월 3일 12시

        assertThat(TaskSchedule.latestAtOrBefore(cron, SEOUL, from, now))
                .isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
    }

    @Test
    @DisplayName("구간 안에 예정 시각이 없으면 가장 늦은 시각도 없다")
    void returnsNullWhenNoTimeInRange() {
        CronExpression cron = TaskSchedule.parseCron("0 9 * * *");

        assertThat(TaskSchedule.latestAtOrBefore(
                        cron, SEOUL, Instant.parse("2026-10-01T01:00:00Z"), Instant.parse("2026-10-01T02:00:00Z")))
                .isNull();
    }

    @Test
    @DisplayName("서머타임이 시작하는 날 없는 2시 30분은 그날 건너뛴다")
    void skipsNonexistentTimeOnDaylightSavingStart() {
        CronExpression cron = TaskSchedule.parseCron("30 2 * * *");
        // 2026-03-08 이 뉴욕의 서머타임 시작일이다. 2시에서 3시로 건너뛴다.
        Instant beforeGap = Instant.parse("2026-03-07T12:00:00Z");

        Instant first = TaskSchedule.nextAfter(cron, NEW_YORK, beforeGap);

        assertThat(first)
                .isEqualTo(
                        LocalDateTime.parse("2026-03-09T02:30").atZone(NEW_YORK).toInstant());
    }

    @Test
    @DisplayName("서머타임이 끝나는 날 겹친 1시 30분은 서로 다른 두 순간이다")
    void firesTwiceOnOverlappingTimeAtDaylightSavingEnd() {
        CronExpression cron = TaskSchedule.parseCron("30 1 * * *");
        Instant beforeOverlap = Instant.parse("2026-11-01T04:00:00Z");

        Instant first = TaskSchedule.nextAfter(cron, NEW_YORK, beforeOverlap);
        Instant second = TaskSchedule.nextAfter(cron, NEW_YORK, first);

        assertThat(first).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(second).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    }

    @Test
    @DisplayName("없는 시간대 이름은 TASK_SCHEDULE_INVALID 다")
    void rejectsUnknownTimeZone() {
        assertInvalid(() -> TaskSchedule.parseZone("Mars/Olympus"));
    }

    @Test
    @DisplayName("한 번 도는 시각은 그 시간대로 해석하고 지금보다 뒤가 아니면 TASK_SCHEDULE_INVALID 다")
    void readsOnceInZoneAndRejectsPast() {
        assertThat(TaskSchedule.onceAt(LocalDateTime.parse("2026-10-04T10:00"), SEOUL, NOW))
                .isEqualTo(Instant.parse("2026-10-04T01:00:00Z"));
        assertInvalid(() -> TaskSchedule.onceAt(LocalDateTime.parse("2026-10-04T09:00"), SEOUL, NOW));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_SCHEDULE_INVALID));
    }
}
