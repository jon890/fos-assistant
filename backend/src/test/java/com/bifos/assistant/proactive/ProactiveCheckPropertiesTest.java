package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.proactive.application.ProactiveCheckProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 살펴보기 설정이 범위를 벗어나거나 Hermes 실행 한도보다 길면 기동에서 멈추는 것을 고정한다. */
class ProactiveCheckPropertiesTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindBoth.class, ProactiveCheckProperties.RunTimeoutCheck.class);

    @Test
    @DisplayName("설정을 적지 않으면 기본값으로 뜬다")
    void startsWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ProactiveCheckProperties properties = context.getBean(ProactiveCheckProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.maxDuration()).isEqualTo(Duration.ofMinutes(4));
            assertThat(properties.maxToolCalls()).isEqualTo(40);
            assertThat(properties.maxDelegations()).isEqualTo(3);
            assertThat(properties.sessionMaxChecks()).isEqualTo(14);
            assertThat(properties.digestWindow()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.digestMaxItems()).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("max-duration 이 hermes.run-timeout 과 같거나 길면 기동이 실패한다")
    void failsToStartWhenMaxDurationIsNotShorterThanRunTimeout() {
        for (String maxDuration : new String[] {"2m", "3m"}) {
            runner.withPropertyValues("hermes.run-timeout=2m", "assistant.proactive-check.max-duration=" + maxDuration)
                    .run(context -> assertThat(context)
                            .as("max-duration=%s, run-timeout=2m", maxDuration)
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("max-duration")
                            .hasMessageContaining("hermes.run-timeout"));
        }
    }

    @Test
    @DisplayName("max-duration 이 hermes.run-timeout 보다 짧으면 뜬다")
    void startsWhenMaxDurationIsShorterThanRunTimeout() {
        runner.withPropertyValues("hermes.run-timeout=1s", "assistant.proactive-check.max-duration=999ms")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("정수 한도가 하한보다 작으면 어느 값인지 알리며 멈춘다")
    void stopsNamingWhichValueWhenIntegerIsBelowMinimum() {
        assertThatThrownBy(() -> new ProactiveCheckProperties(true, MINUTE, 0, 3, 14, MINUTE, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-tool-calls");
        assertThatThrownBy(() -> new ProactiveCheckProperties(true, MINUTE, 40, -1, 14, MINUTE, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-delegations");
        assertThatThrownBy(() -> new ProactiveCheckProperties(true, MINUTE, 40, 3, 0, MINUTE, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-max-checks");
        assertThatThrownBy(() -> new ProactiveCheckProperties(true, MINUTE, 40, 3, 14, MINUTE, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest-max-items");
    }

    @Test
    @DisplayName("위임 한도는 0 이어도 받는다")
    void acceptsZeroDelegations() {
        ProactiveCheckProperties properties = new ProactiveCheckProperties(true, MINUTE, 1, 0, 1, MINUTE, 1);

        assertThat(properties.maxDelegations()).isZero();
    }

    @Test
    @DisplayName("시간이 없거나 0 이하이면 멈춘다")
    void stopsWhenDurationIsMissingOrNotPositive() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new ProactiveCheckProperties(true, invalid, 40, 3, 14, MINUTE, 20))
                    .as("max-duration=%s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-duration");
            assertThatThrownBy(() -> new ProactiveCheckProperties(true, MINUTE, 40, 3, 14, invalid, 20))
                    .as("digest-window=%s", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("digest-window");
        }
    }

    /** 앱의 설정 검색 대신 두 설정 묶음만 묶는다. */
    @EnableConfigurationProperties({ProactiveCheckProperties.class, HermesProperties.class})
    static class BindBoth {}
}
