package com.bifos.assistant.proactive.application;

import com.bifos.assistant.hermes.HermesProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 먼저 살펴보기의 설정이다(ADR-077). 칸과 기본값의 뜻은 {@code docs/backend/proactive-check.md} 의 「설정」 이 갖는다.
 *
 * @param enabled 꺼 두면 살펴보기를 시작하지 않는다
 * @param maxDuration 살펴보기 turn 하나가 돌 수 있는 시간. {@code hermes.run-timeout} 보다 짧아야 한다
 * @param maxToolCalls 살펴보기 turn 하나가 시작할 수 있는 도구 호출 수
 * @param maxDelegations 살펴보기 트리에서 맡길 수 있는 위임 자식 수. 0 이면 맡기지 않는다
 * @param sessionMaxChecks 점검 대화의 루트 session 하나로 보내는 살펴보기 수. 닿으면 session 을 바꾼다
 * @param digestWindow 최근에 알린 발견으로 읽는 기간
 * @param digestMaxItems 다음 살펴보기의 입력에 싣는 최근 발견 수
 */
@Validated
@ConfigurationProperties(prefix = "assistant.proactive-check")
public record ProactiveCheckProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4m") Duration maxDuration,
        @DefaultValue("40") int maxToolCalls,
        @DefaultValue("3") int maxDelegations,
        @DefaultValue("14") int sessionMaxChecks,
        @DefaultValue("30d") Duration digestWindow,
        @DefaultValue("20") int digestMaxItems) {

    private static final String PREFIX = "assistant.proactive-check.";

    /** 범위를 벗어난 값이면 기동을 멈춘다. 기동은 성공하는데 살펴보기가 모두 곧바로 멈추거나 거절돼 알아채지 못한다. */
    public ProactiveCheckProperties {
        requirePositive("max-duration", maxDuration);
        requireAtLeast("max-tool-calls", maxToolCalls, 1);
        requireAtLeast("max-delegations", maxDelegations, 0);
        requireAtLeast("session-max-checks", sessionMaxChecks, 1);
        requirePositive("digest-window", digestWindow);
        requireAtLeast("digest-max-items", digestMaxItems, 1);
    }

    /**
     * {@code max-duration} 이 Hermes 실행 한도보다 짧은지 본다. 같거나 길면 살펴보기의 시간 상한보다 실행 한도가 먼저 닿아,
     * {@code CHECK_TIME_LIMIT} 으로 멈추지 않고 보통 실패로 끝난다.
     */
    public void requireShorterThan(Duration runTimeout) {
        if (maxDuration.compareTo(runTimeout) >= 0) {
            throw new IllegalStateException(PREFIX + "max-duration must be shorter than hermes.run-timeout: "
                    + maxDuration + " >= " + runTimeout);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(PREFIX + name + " must be longer than zero: " + value);
        }
    }

    private static void requireAtLeast(String name, int value, int min) {
        if (value < min) {
            throw new IllegalStateException(PREFIX + name + " must be at least " + min + ": " + value);
        }
    }

    /** 두 설정을 함께 읽어 기동할 때 {@link #requireShorterThan} 을 부른다. 한 설정 묶음의 생성자는 다른 묶음의 값을 보지 못한다. */
    @Configuration(proxyBeanMethods = false)
    public static class RunTimeoutCheck {

        public RunTimeoutCheck(ProactiveCheckProperties properties, HermesProperties hermes) {
            properties.requireShorterThan(hermes.runTimeout());
        }
    }
}
