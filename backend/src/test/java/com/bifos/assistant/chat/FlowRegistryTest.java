package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.Flow;
import com.bifos.assistant.chat.application.FlowRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 흐름 이름이 등록됐는지 묻는 동작을 본다. */
class FlowRegistryTest {

    private final Flow flow = mock(Flow.class);
    private final FlowRegistry registry;

    FlowRegistryTest() {
        when(flow.name()).thenReturn("research-and-build");
        registry = new FlowRegistry(List.of(flow), mock(AgentRepository.class));
    }

    @Test
    @DisplayName("등록한 흐름 이름은 안다")
    void knowsRegisteredName() {
        assertThat(registry.known("research-and-build")).isTrue();
    }

    @Test
    @DisplayName("모르는 이름과 null 과 빈 문자열은 예외 없이 모른다고 답한다")
    void unknownNullAndEmptyAreNotKnown() {
        assertThat(registry.known("other")).isFalse();
        assertThat(registry.known(null)).isFalse();
        assertThat(registry.known("")).isFalse();
    }
}
