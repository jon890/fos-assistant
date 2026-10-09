package com.bifos.assistant.task.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 예약 작업의 설정이다(ADR-076, ADR-077, ADR-079). 키는 {@code assistant.task} 이고 기본값은 각 칸의 {@code @DefaultValue} 가 갖는다.
 *
 * @param dispatchCron 발화기와 시작 단계가 도는 때. 일정이 이 값을 설정 이름으로 읽는다. {@code -} 이면 돌지 않는다
 * @param missedGrace 이만큼 늦은 예정 시각은 놓친 것으로 보지 않는다
 * @param startTimeout {@code QUEUED} 줄이 만들어진 뒤 이만큼 열리지 못하면 건너뛴다
 * @param maxPerUser 사용자당 보관하지 않은 작업 수. 1 이상
 * @param minInterval 반복 시각의 최소 간격
 * @param maxRunsPerDay 사용자당 24시간 안의 발화 수. 1 이상
 * @param defaultTimeZone 시간대를 비운 작업의 시간대
 */
@Validated
@ConfigurationProperties(prefix = "assistant.task")
public record TaskProperties(
        @DefaultValue("*/30 * * * * *") String dispatchCron,
        @DefaultValue("2m") Duration missedGrace,
        @DefaultValue("10m") Duration startTimeout,
        @DefaultValue("10") int maxPerUser,
        @DefaultValue("15m") Duration minInterval,
        @DefaultValue("48") int maxRunsPerDay,
        @DefaultValue("Asia/Seoul") String defaultTimeZone) {

    /** 잘못된 값이면 기동을 멈춘다. 작업을 하나도 만들지 못하거나 모든 시각이 거절되는데 기동은 성공해 알아채지 못한다. */
    public TaskProperties {
        requirePositive("missed-grace", missedGrace);
        requirePositive("start-timeout", startTimeout);
        requirePositive("min-interval", minInterval);
        requireAtLeastOne("max-per-user", maxPerUser);
        requireAtLeastOne("max-runs-per-day", maxRunsPerDay);
        if (defaultTimeZone == null || defaultTimeZone.isBlank()) {
            throw new IllegalStateException("assistant.task.default-time-zone must not be blank");
        }
        try {
            ZoneId.of(defaultTimeZone);
        } catch (DateTimeException e) {
            throw new IllegalStateException(
                    "assistant.task.default-time-zone is not a time zone: " + defaultTimeZone, e);
        }
    }

    public ZoneId defaultZone() {
        return ZoneId.of(defaultTimeZone);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("assistant.task." + name + " must be longer than zero: " + value);
        }
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalStateException("assistant.task." + name + " must be at least 1: " + value);
        }
    }
}
