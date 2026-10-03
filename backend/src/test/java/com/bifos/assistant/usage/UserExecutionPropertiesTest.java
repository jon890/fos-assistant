package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.usage.application.UserExecutionProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserExecutionPropertiesTest {

    @Test
    @DisplayName("한도 1 이상, 예비 자리 0 이상이고 한도보다 작고, 원격 종료 상한이 비었거나 양수면 만들어진다")
    void acceptsValidValues() {
        UserExecutionProperties defaults = new UserExecutionProperties(4, 1, null);
        UserExecutionProperties smallest = new UserExecutionProperties(1, 0, Duration.ofSeconds(1));

        assertThat(defaults.maxRunning()).isEqualTo(4);
        assertThat(defaults.backgroundReserve()).isEqualTo(1);
        assertThat(defaults.remoteEndMaxWait()).isNull();
        assertThat(smallest.remoteEndMaxWait()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("한도가 0 이면 기동을 멈춘다")
    void rejectsZeroMaxRunning() {
        assertThatThrownBy(() -> new UserExecutionProperties(0, 0, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-running");
    }

    @Test
    @DisplayName("예비 자리가 음수면 기동을 멈춘다")
    void rejectsNegativeBackgroundReserve() {
        assertThatThrownBy(() -> new UserExecutionProperties(4, -1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("background-reserve");
    }

    @Test
    @DisplayName("예비 자리가 한도와 같으면 백그라운드 실행이 영영 돌지 못하므로 기동을 멈춘다")
    void rejectsBackgroundReserveEqualToMaxRunning() {
        assertThatThrownBy(() -> new UserExecutionProperties(4, 4, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("background-reserve");
    }

    @Test
    @DisplayName("원격 종료 상한이 0 이면 기동을 멈춘다")
    void rejectsZeroRemoteEndMaxWait() {
        assertThatThrownBy(() -> new UserExecutionProperties(4, 1, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remote-end-max-wait");
    }
}
