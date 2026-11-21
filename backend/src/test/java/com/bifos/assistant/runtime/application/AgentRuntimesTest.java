package com.bifos.assistant.runtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.runtime.application.port.AgentRuntime;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.shared.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentRuntimesTest {

    private static final RuntimeTarget TARGET = new RuntimeTarget(RuntimeKind.HERMES, "http://runtime.test", "unit-1");


    @Test
    @DisplayName("대상의 종류에 맞는 구현을 고른다")
    void picksRuntimeByKind() {
        AgentRuntime hermes = runtimeOf(RuntimeKind.HERMES);

        assertThat(new AgentRuntimes(List.of(hermes)).of(TARGET)).isSameAs(hermes);
    }


    @Test
    @DisplayName("맞는 구현이 없으면 실행 실패 오류를 던진다")
    void failsWhenNoRuntimeForKind() {
        AgentRuntimes runtimes = new AgentRuntimes(List.of());

        assertThatThrownBy(() -> runtimes.of(TARGET)).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("같은 종류의 구현이 둘이면 만들지 못한다")
    void rejectsDuplicateKinds() {
        List<AgentRuntime> duplicated = List.of(runtimeOf(RuntimeKind.HERMES), runtimeOf(RuntimeKind.HERMES));

        assertThatThrownBy(() -> new AgentRuntimes(duplicated)).isInstanceOf(IllegalStateException.class);
    }


    private static AgentRuntime runtimeOf(RuntimeKind kind) {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.kind()).thenReturn(kind);
        return runtime;
    }
}
