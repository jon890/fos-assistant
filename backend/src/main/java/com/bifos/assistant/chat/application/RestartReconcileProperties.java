package com.bifos.assistant.chat.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 기동할 때 {@code RUNNING} 으로 남은 실행을 Hermes 에 물어 정하는 설정이다(ADR-060).
 *
 * @param enabled 꺼 두면 기동 때 잡지도 묻지도 않는다. 그 줄은 {@code RUNNING} 으로 남는다
 * @param maxWait 다시 붙어 기다리는 상한. null 이면 쓰는 쪽이 {@code hermes.run-timeout} 을 쓴다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.restart-reconcile")
public record RestartReconcileProperties(
        @DefaultValue("true") boolean enabled, Duration maxWait) {

    /** 0 이하이면 기동을 멈춘다. 기동은 성공하는데 남은 실행이 모두 곧바로 실패로 적혀 알아채지 못한다. */
    public RestartReconcileProperties {
        if (maxWait != null && (maxWait.isZero() || maxWait.isNegative())) {
            throw new IllegalStateException("assistant.restart-reconcile.max-wait must be positive: " + maxWait);
        }
    }
}
