package com.bifos.assistant.notification.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 알림의 설정이다(ADR-070).
 *
 * @param retention 알림을 남기는 기간. 읽었는지와 상관없이 지난 줄을 지운다
 * @param cleanupCron 오래된 줄을 지우는 때. 일정이 이 값을 설정 이름으로 읽는다. {@code -} 이면 돌지 않는다
 * @param streamHeartbeat 알림 SSE 에 주석 {@code ping} 을 보내는 간격
 */
@Validated
@ConfigurationProperties(prefix = "assistant.notification")
public record NotificationProperties(
        @DefaultValue("90d") Duration retention,
        @DefaultValue("0 30 4 * * *") String cleanupCron,
        @DefaultValue("20s") Duration streamHeartbeat) {

    /** 시간 가운데 하나라도 0 이하이면 기동을 멈춘다. 만들자마자 지우거나 주석 줄을 쉬지 않고 보내는데 기동은 성공해 알아채지 못한다. */
    public NotificationProperties {
        requirePositive("retention", retention);
        requirePositive("stream-heartbeat", streamHeartbeat);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("assistant.notification." + name + " must be longer than zero: " + value);
        }
    }
}
