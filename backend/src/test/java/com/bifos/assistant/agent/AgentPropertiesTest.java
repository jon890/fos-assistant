package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지운 에이전트를 정리하기까지 기다리는 기간의 기본값과 거절하는 값을 본다. */
class AgentPropertiesTest {

    @Test
    @DisplayName("설정이 없으면 7일을 기다린다")
    void defaultsPurgeAfterToSevenDays() {
        assertThat(new AgentProperties(null, null).purgeAfter()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("기다리는 기간이 0 이면 기동을 막는다")
    void rejectsZeroPurgeAfter() {
        assertThatThrownBy(() -> new AgentProperties(null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("assistant.agents.purge-after must be positive");
    }
}
