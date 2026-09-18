package com.bifos.assistant.agent.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 막힌 provider 를 얼마나 오래 건너뛸지 정한다.
 *
 * <p>Hermes 가 남은 시간을 HTTP 로 알려주지 않아 우리가 정한다. 너무 짧으면 매번 실패 왕복이 한 번 더
 * 들고, 너무 길면 풀린 뒤에도 쓰지 않는다.
 *
 * @param providerCooldown 막힌 것으로 본 provider 를 건너뛰는 시간
 */
@ConfigurationProperties(prefix = "assistant.model")
public record ModelSelectionProperties(Duration providerCooldown) {

    private static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(30);

    public ModelSelectionProperties {
        providerCooldown = providerCooldown == null ? DEFAULT_COOLDOWN : providerCooldown;
    }
}
