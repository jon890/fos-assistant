package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.memory.application.MemorySources;
import com.bifos.assistant.memory.application.model.MemorySource;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionAgentQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기억 목록에 보일 「남긴 에이전트」 를 읽는 사용자가 볼 수 있는 만큼만 싣는지 확인한다. */
class MemorySourcesTest {

    private static final CurrentUser USER = new CurrentUser(7L, "user@example.com", "사용자A", 1L, UserRole.MEMBER);

    private final ExecutionAgentQuery executions = mock(ExecutionAgentQuery.class);
    private final AgentService agents = mock(AgentService.class);
    private final MemorySources sources = new MemorySources(executions, agents);

    @Test
    @DisplayName("볼 수 있는 에이전트는 이름을, 볼 수 없거나 모르는 에이전트는 이름 없이, 지운 에이전트는 지웠다고 싣는다")
    void describesSourceByVisibility() {
        Memory direct = memory(1L, null);
        Memory visible = memory(2L, 20L);
        Memory hidden = memory(3L, 30L);
        Memory deleted = memory(4L, 40L);
        Memory unknownExecution = memory(5L, 50L);
        when(executions.agentIdsOf(any())).thenReturn(Map.of(20L, 200L, 30L, 300L, 40L, 400L));
        Map<Long, Agent> byId = new HashMap<>();
        byId.put(200L, agent("집안일 도우미", true, false));
        byId.put(300L, agent("다른 사람 비서", false, false));
        byId.put(400L, agent("옛 비서", true, true));
        when(agents.byIds(any())).thenReturn(byId);

        Map<Long, MemorySource> result = sources.of(USER, List.of(direct, visible, hidden, deleted, unknownExecution));

        assertThat(result).doesNotContainKey(1L);
        assertThat(result.get(2L)).isEqualTo(new MemorySource("집안일 도우미", false));
        assertThat(result.get(3L)).isEqualTo(MemorySource.UNNAMED);
        assertThat(result.get(4L)).isEqualTo(new MemorySource(null, true));
        assertThat(result.get(5L)).isEqualTo(MemorySource.UNNAMED);
    }

    private static Memory memory(Long id, Long executionId) {
        Memory memory = mock(Memory.class);
        when(memory.id()).thenReturn(id);
        when(memory.proposedByExecutionId()).thenReturn(executionId);
        return memory;
    }

    private static Agent agent(String name, boolean readable, boolean deleted) {
        Agent agent = mock(Agent.class);
        when(agent.name()).thenReturn(name);
        when(agent.isReadableBy(USER.id())).thenReturn(readable);
        when(agent.isDeleted()).thenReturn(deleted);
        return agent;
    }
}
