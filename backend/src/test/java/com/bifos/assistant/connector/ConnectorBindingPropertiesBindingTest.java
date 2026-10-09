package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.ConnectorBindingProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 설정 파일의 {@code assistant.connector.binding} 값이 {@link ConnectorBindingProperties} 로 들어오는지 본다. */
class ConnectorBindingPropertiesBindingTest {
    private static final String PREFIX = "assistant.connector.binding";

    @Test
    @DisplayName("drift-batch 를 1 로 주면 정의 어긋남 점검이 한 주기에 하나만 읽는다")
    void bindsDriftBatch() {
        ConnectorBindingProperties bound = bind(Map.of(PREFIX + ".drift-batch", "1"));

        assertThat(bound.driftBatch()).isEqualTo(1);
        assertThat(bound.applyDelay()).isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    @DisplayName("drift-batch 를 주지 않으면 한 주기에 20 개까지 읽는다")
    void defaultsDriftBatchToTwenty() {
        assertThat(bind(Map.of()).driftBatch()).isEqualTo(20);
    }

    @Test
    @DisplayName("drift-batch 를 0 으로 주면 바인딩이 실패해 기동이 멈춘다")
    void rejectsZeroDriftBatch() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".drift-batch", "0")))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drift-batch");
    }

    private static ConnectorBindingProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate(PREFIX, ConnectorBindingProperties.class);
    }
}
