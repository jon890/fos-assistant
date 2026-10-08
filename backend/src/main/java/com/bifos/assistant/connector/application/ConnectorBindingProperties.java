package com.bifos.assistant.connector.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 바인딩의 반영 예정 확인 설정이다(ADR-20261007 / connector-live-reload).
 *
 * @param applyDelay 재시작 없이 반영될 설치를 보낸 뒤 반영 맞추기를 스스로 돌리기까지 기다리는 시간. 공유 gateway 의 MCP
 *     설정 맞추기 주기(60초) 둘과 연결 시간이다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector.binding")
public record ConnectorBindingProperties(
        @DefaultValue("150s") Duration applyDelay) {

    /** 지연이 0 이하이면 기동을 멈춘다. gateway 가 연결하기 전에 확인해 바인딩이 늘 {@code PENDING} 으로 남는데 기동은 성공해 알아채지 못한다. */
    public ConnectorBindingProperties {
        if (applyDelay == null || applyDelay.isZero() || applyDelay.isNegative()) {
            throw new IllegalStateException(
                    "assistant.connector.binding.apply-delay must be longer than zero: " + applyDelay);
        }
    }
}
