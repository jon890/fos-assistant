package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.ConnectorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 커넥터 호출 한도가 0 이하이면 기동에서 멈추는 것을 고정한다. */
class ConnectorPropertiesTest {

    @Test
    @DisplayName("두 값이 1 이상이면 그대로 쓴다")
    void usesValuesAsIsWhenBothAreAtLeastOne() {
        ConnectorProperties properties = new ConnectorProperties(1, 1);

        assertThat(properties.maxConcurrentCalls()).isEqualTo(1);
        assertThat(properties.callsPerMinute()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도가 0 이하이면 어느 값인지 알리며 멈춘다")
    void stopsNamingWhichValueWhenLimitIsNotPositive() {
        assertThatThrownBy(() -> new ConnectorProperties(0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistant.connector.max-concurrent-calls");
        assertThatThrownBy(() -> new ConnectorProperties(1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistant.connector.calls-per-minute");
        assertThatThrownBy(() -> new ConnectorProperties(-1, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-calls");
        assertThatThrownBy(() -> new ConnectorProperties(1, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calls-per-minute");
    }
}
