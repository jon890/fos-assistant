package com.bifos.assistant.chat.turn.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ActivityUseTest {
    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");


    @Test
    @DisplayName("트리의 도구와 도우미를 처음 쓴 차례로 한 번씩 담는다")
    void ordersFirstUseAcrossTree() {
        AgentExecution root = execution(1L);
        AgentExecution child = execution(2L);
        Map<Long, List<ExecutionEvent>> events = Map.of(
            1L, List.of(
                tool(1L, 1, "web_search", 0),
                tool(1L, 2, "web_search", 1),
                tool(1L, 3, "web_extract", 5),
                tool(1L, 4, "web_search", 6)),
            2L, List.of(tool(2L, 1, "mcp__gmail__get_message", 3)));

        assertThat(ActivityUse.firstUses(1L, List.of(root, child), events))
            .extracting(ActivityUse::kind, ActivityUse::toolName)
            .containsExactly(
                tuple(ActivityUse.TOOL, "web_search"),
                tuple(ActivityUse.SUBAGENT, null),
                tuple(ActivityUse.TOOL, "mcp__gmail__get_message"),
                tuple(ActivityUse.TOOL, "web_extract"));
    }


    private static AgentExecution execution(Long id) {
        AgentExecution execution = AgentExecution.builder().build();
        ReflectionTestUtils.setField(execution, "id", id);
        return execution;
    }


    private static ExecutionEvent tool(Long executionId, int sequence, String name, int seconds) {
        return ExecutionEvent.builder()
            .executionId(executionId)
            .sequence(sequence)
            .eventType(ExecutionEventType.TOOL_STARTED)
            .toolName(name)
            .occurredAt(T0.plusSeconds(seconds))
            .build();
    }
}
