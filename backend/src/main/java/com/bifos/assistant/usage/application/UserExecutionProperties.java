package com.bifos.assistant.usage.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 한 사용자가 Hermes 에 동시에 맡길 수 있는 실행의 한도다(ADR-069).
 *
 * @param maxRunning 한 사용자가 동시에 쥘 수 있는 자리 수. turn 자리와 {@code RUNNING} 실행 줄, 원격 종료 확인 자리의 합이다
 * @param backgroundReserve 추천 질문과 Memory 제안이 줄을 만든 뒤에도 남겨 둘 자리 수
 * @param remoteEndMaxWait Hermes 에서 끝났는지 모르는 run 의 자리를 쥐는 상한. null 이면 쓰는 쪽이 {@code hermes.run-timeout} 을 쓴다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.user-execution")
public record UserExecutionProperties(int maxRunning, int backgroundReserve, Duration remoteEndMaxWait) {

    /**
     * 값이 범위를 벗어나면 기동을 멈춘다. {@code max-running} 이 0 이면 모든 turn 이 거절되고, 예비 자리가 한도 이상이면
     * 백그라운드 실행이 영영 돌지 못하는데 기동은 성공해 알아채지 못한다.
     */
    public UserExecutionProperties {
        if (maxRunning < 1) {
            throw new IllegalStateException("assistant.user-execution.max-running must be at least 1: " + maxRunning);
        }
        if (backgroundReserve < 0 || backgroundReserve >= maxRunning) {
            throw new IllegalStateException(
                    "assistant.user-execution.background-reserve must be at least 0 and less than max-running: "
                            + backgroundReserve);
        }
        if (remoteEndMaxWait != null && (remoteEndMaxWait.isZero() || remoteEndMaxWait.isNegative())) {
            throw new IllegalStateException(
                    "assistant.user-execution.remote-end-max-wait must be positive: " + remoteEndMaxWait);
        }
    }
}
