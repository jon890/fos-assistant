package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.orchestration.config.DelegationProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 위임 한도가 비었거나 0 이하이면 기동에서 멈추고, 상태 대기 상한을 비우면 기본값으로 뜨는 것을 고정한다. */
class DelegationPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Bind.class)
        .withPropertyValues(
            "assistant.delegation.max-depth=2",
            "assistant.delegation.max-concurrent-children=4",
            "assistant.delegation.max-active=16",
            "assistant.delegation.submit-timeout=30s",
            "assistant.delegation.output-max-chars=100");


    @Test
    @DisplayName("모든 값이 1 이상이면 그대로 쓴다")
    void usesValuesAsIsWhenAllAreAtLeastOne() {
        DelegationProperties properties = new DelegationProperties(1, 1, 1, Duration.ofMillis(1), 1, Duration.ofMillis(1), Duration.ZERO);

        assertThat(properties.maxDepth()).isEqualTo(1);
        assertThat(properties.submitTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(properties.statusWaitMax()).isEqualTo(Duration.ofMillis(1));
    }


    @Test
    @DisplayName("정수 한도가 0 이면 어느 값인지 알리며 멈춘다")
    void stopsNamingWhichValueWhenIntegerLimitIsZero() {
        assertThatThrownBy(() -> new DelegationProperties(0, 4, 16, TIMEOUT, 100, WAIT, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-depth");
        assertThatThrownBy(() -> new DelegationProperties(2, 0, 16, TIMEOUT, 100, WAIT, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-concurrent-children");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, 0, TIMEOUT, 100, WAIT, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-active");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, TIMEOUT, 0, WAIT, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("output-max-chars");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, -1, TIMEOUT, 100, WAIT, Duration.ZERO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-active");
    }


    @Test
    @DisplayName("제출 대기 시간이 없거나 0 이하이면 멈춘다")
    void stopsWhenSubmitWaitTimeIsMissingOrNotPositive() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, invalid, 100, WAIT, Duration.ZERO))
                .as("submit-timeout=%s", invalid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit-timeout");
        }
    }


    @Test
    @DisplayName("상태 대기 상한이 없거나 0 이하이면 멈춘다")
    void stopsWhenStatusWaitMaxIsMissingOrNotPositive() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, TIMEOUT, 100, invalid, Duration.ZERO))
                .as("status-wait-max=%s", invalid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status-wait-max");
        }
    }


    @Test
    @DisplayName("상태 대기 상한을 적지 않으면 20초로 뜬다")
    void startsWithTwentySecondStatusWaitMaxWhenUnset() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DelegationProperties.class).statusWaitMax())
                .isEqualTo(Duration.ofSeconds(20));
        });
    }


    @Test
    @DisplayName("상태 대기 상한을 0 으로 적으면 기동이 실패한다")
    void failsToStartWhenStatusWaitMaxIsZero() {
        runner.withPropertyValues("assistant.delegation.status-wait-max=0s")
            .run(context -> assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status-wait-max"));
    }


    @Test
    @DisplayName("맡김 대기 상한을 적지 않으면 2분으로 뜨고 0 은 받는다")
    void startsWithTwoMinuteDelegateWaitAndAcceptsZero() {
        runner.run(context -> assertThat(context.getBean(DelegationProperties.class).delegateWaitMax())
            .isEqualTo(Duration.ofMinutes(2)));
        runner.withPropertyValues("assistant.delegation.delegate-wait-max=0s")
            .run(context -> assertThat(context.getBean(DelegationProperties.class).delegateWaitMax()).isZero());
    }


    @Test
    @DisplayName("맡김 대기 상한이 없거나 음수이면 멈춘다")
    void stopsWhenDelegateWaitMaxIsMissingOrNegative() {
        for (Duration invalid : new Duration[] {null, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, TIMEOUT, 100, WAIT, invalid))
                .as("delegate-wait-max=%s", invalid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegate-wait-max");
        }
    }


    @EnableConfigurationProperties(DelegationProperties.class)
    static class Bind {
    }
}
