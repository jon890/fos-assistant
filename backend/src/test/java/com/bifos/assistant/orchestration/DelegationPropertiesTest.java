package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.orchestration.application.DelegationProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 위임 한도가 비었거나 0 이하이면 기동에서 멈추는 것을 고정한다. */
class DelegationPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    @DisplayName("모든 값이 1 이상이면 그대로 쓴다")
    void usesValuesAsIsWhenAllAreAtLeastOne() {
        DelegationProperties properties = new DelegationProperties(1, 1, 1, Duration.ofMillis(1), 1);

        assertThat(properties.maxDepth()).isEqualTo(1);
        assertThat(properties.submitTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("정수 한도가 0 이면 어느 값인지 알리며 멈춘다")
    void stopsNamingWhichValueWhenIntegerLimitIsZero() {
        assertThatThrownBy(() -> new DelegationProperties(0, 4, 16, TIMEOUT, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-depth");
        assertThatThrownBy(() -> new DelegationProperties(2, 0, 16, TIMEOUT, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-concurrent-children");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, 0, TIMEOUT, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-active");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, TIMEOUT, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("output-max-chars");
        assertThatThrownBy(() -> new DelegationProperties(2, 4, -1, TIMEOUT, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-active");
    }

    @Test
    @DisplayName("제출 대기 시간이 없거나 0 이하이면 멈춘다")
    void stopsWhenSubmitWaitTimeIsMissingOrNotPositive() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, invalid, 100))
                    .as("submit-timeout=%s", invalid)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("submit-timeout");
        }
    }
}
