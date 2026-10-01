package com.bifos.assistant.chat.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 맡긴 일의 결과가 끝나면 부모 대화의 turn 을 자동으로 여는 설정이다(ADR-040).
 *
 * @param enabled 꺼 두면 결과가 끝나도 자동 turn 을 열지 않는다
 * @param maxAutoTurns 사용자의 질문 없이 이어서 여는 turn 의 수. 닿으면 알림 줄만 남긴다
 */
@ConfigurationProperties(prefix = "assistant.delegation-wake")
public record DelegationWakeProperties(
        @DefaultValue("true") boolean enabled, @DefaultValue("10") int maxAutoTurns) {

    /** 1 미만이면 기동을 멈춘다. 0 이면 결과가 와도 한 번도 열지 않는데 기동은 성공해 알아채지 못한다. */
    public DelegationWakeProperties {
        if (maxAutoTurns < 1) {
            throw new IllegalStateException(
                    "assistant.delegation-wake.max-auto-turns must be at least 1: " + maxAutoTurns);
        }
    }
}
