package com.bifos.assistant.proactive.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 매일 루프의 설치 설정이다. 뜻은 {@code docs/backend/proactive-loop.md} 의 「설정」 이 갖는다.
 *
 * @param enabled 설치가 매일 루프를 연다. 기본은 꺼짐이고, 꺼져 있으면 사용자 설정과 상관없이 잇지 않는다
 * @param provider 평가에 쓸 {@code DecisionProvider} 이름. 설치된 adapter 여야 한다
 * @param maxRunsPerDay 사용자 한 명의 최근 20시간 시도 상한. 1 이상이다
 * @param surfaceWindow 이 기간 안의 시도가 낸 판정만 지금 화면에 보인다. 0 보다 크다
 */
@ConfigurationProperties(prefix = "assistant.proactive-loop")
@Validated
public record ProactiveLoopProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("hermes") String provider,
        @DefaultValue("1") int maxRunsPerDay,
        @DefaultValue("7d") Duration surfaceWindow) {

    public ProactiveLoopProperties {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("proactive-loop provider must not be blank");
        }
        if (maxRunsPerDay < 1) {
            throw new IllegalArgumentException("proactive-loop max-runs-per-day must be at least 1");
        }
        if (surfaceWindow == null || surfaceWindow.isZero() || surfaceWindow.isNegative()) {
            throw new IllegalArgumentException("proactive-loop surface-window must be greater than zero");
        }
    }
}
