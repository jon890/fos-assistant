package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.orchestration.application.DelegationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 위임 한도가 비었거나 0 이하이면 기동에서 멈추는 것을 고정한다. */
class DelegationPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void 모든_값이_1_이상이면_그대로_쓴다() {
        DelegationProperties properties = new DelegationProperties(1, 1, 1, Duration.ofMillis(1), 1);

        assertThat(properties.maxDepth()).isEqualTo(1);
        assertThat(properties.submitTimeout()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void 정수_한도가_0_이면_어느_값인지_알리며_멈춘다() {
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
    void 제출_대기_시간이_없거나_0_이하이면_멈춘다() {
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new DelegationProperties(2, 4, 16, invalid, 100))
                    .as("submit-timeout=%s", invalid)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("submit-timeout");
        }
    }
}
