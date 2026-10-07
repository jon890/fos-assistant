package com.bifos.assistant.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LivePropertiesTest {

    /** 설정 record 를 흉내 낸 값이다. */
    private record Sample(int limit, Duration timeout) {}

    @Test
    @DisplayName("fixed 는 받은 값을 몇 번 읽어도 그대로 돌려주고 type 은 받은 타입이다")
    void fixedReturnsSameValueAndType() {
        Sample value = new Sample(3, Duration.ofSeconds(5));

        LiveProperties<Sample> live = LiveProperties.fixed(Sample.class, value);

        assertThat(live.current()).as("첫 읽기").isSameAs(value);
        assertThat(live.current()).as("다시 읽기").isSameAs(value);
        assertThat(live.type()).isEqualTo(Sample.class);
    }

    @Test
    @DisplayName("fixed 는 값이나 타입이 비면 만들지 않는다")
    void fixedRejectsNull() {
        assertThatThrownBy(() -> LiveProperties.fixed(Sample.class, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LiveProperties.fixed(null, new Sample(1, Duration.ZERO)))
                .isInstanceOf(NullPointerException.class);
    }
}
