package com.bifos.assistant.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.orchestration.application.dto.PluginCallEnded;
import com.bifos.assistant.orchestration.application.dto.ToolTraceRecord;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.usage.execution.application.ToolTraceWriter;
import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import com.bifos.assistant.usage.execution.application.port.ExecutionConnectorCalls;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.RunningExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class RunTraceIntakeTest {
    private static final String PROFILE = "dad";
    private static final Instant STARTED = Instant.parse("2026-11-24T00:00:10Z");

    private final HermesSessionBindingRepository bindings = mock(HermesSessionBindingRepository.class);
    private final RunningExecutionRepository executions = mock(RunningExecutionRepository.class);
    private final AgentConnectorBindings connectorBindings = mock(AgentConnectorBindings.class);
    private final ExecutionConnectorCalls connectorCalls = mock(ExecutionConnectorCalls.class);
    private final ToolTraceWriter writer = mock(ToolTraceWriter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RunTraceIntake intake = new RunTraceIntake(bindings, executions, connectorBindings, connectorCalls, writer, events);
    private final AgentExecution execution = mock(AgentExecution.class);


    @BeforeEach
    void setUp() {
        when(execution.id()).thenReturn(7L);
        when(execution.agentId()).thenReturn(3L);
        when(execution.treeRootId()).thenReturn(7L);
        when(connectorBindings.connectorToolPrefixes(3L)).thenReturn(Set.of("mcp__mail__"));
        when(bindings.findByProfileNameAndSessionId(anyString(), anyString())).thenReturn(Optional.empty());
        when(executions.findRunningAt(eq(PROFILE), eq("root-1"), any(), any(), any())).thenReturn(List.of(execution));
    }


    @Test
    @DisplayName("실패한 일반 도구의 오류 글을 가린 요약과 받은 그대로의 오류 종류로 넘긴다")
    void redactsErrorOfPlainTool() {
        String token = "sk-" + "b".repeat(40);

        int accepted = intake.receive(PROFILE, List.of(failed("terminal", "인증 실패 " + token, "PermissionError")));

        ToolTrace trace = captured();
        assertThat(accepted).isEqualTo(1);
        assertThat(trace.errorType()).isEqualTo("PermissionError");
        assertThat(trace.errorSummary()).startsWith("인증 실패").doesNotContain(token);
        assertThat(trace.durationMs()).isEqualTo(1_200L);
        assertThat(trace.toolName()).isEqualTo("terminal");
    }


    @Test
    @DisplayName("플러그인 도구와 플러그인 호출 뒤 도구는 오류 종류만 넘긴다")
    void keepsOnlyErrorTypeAfterConnector() {
        intake.receive(PROFILE, List.of(failed("mcp__mail__read", "메일 본문", "tool_error")));
        assertThat(captured().errorSummary()).isNull();

        when(connectorCalls.calledBefore(7L, STARTED)).thenReturn(true);
        intake.receive(PROFILE, List.of(failed("terminal", "파일 없음", "tool_error")));
        ArgumentCaptor<ToolTrace> traces = ArgumentCaptor.forClass(ToolTrace.class);
        verify(writer, times(2)).receive(eq(7L), eq("call_1"), traces.capture());
        assertThat(traces.getAllValues()).extracting(ToolTrace::errorSummary).containsOnlyNulls();
        assertThat(traces.getAllValues()).extracting(ToolTrace::errorType).containsOnly("tool_error");
    }


    @Test
    @DisplayName("성공한 도구는 걸린 시간만 넘기고 오류 칸을 비운다")
    void successCarriesDurationOnly() {
        intake.receive(PROFILE, List.of(record("terminal", "call_1", false, "ignored", "ignored")));

        ToolTrace trace = captured();
        assertThat(trace.errorType()).isNull();
        assertThat(trace.errorSummary()).isNull();
        assertThat(trace.durationMs()).isEqualTo(1_200L);
    }


    @Test
    @DisplayName("호출 번호가 없거나 등록된 하위 에이전트 session 이거나 실행이 하나로 정해지지 않으면 버린다")
    void dropsUnresolvedRecords() {
        assertThat(intake.receive(PROFILE, List.of(record("read_file", "", true, "x", "y")))).isZero();

        when(bindings.findByProfileNameAndSessionId(PROFILE, "child-1")).thenReturn(Optional.of(mock(HermesSessionBinding.class)));
        ToolTraceRecord child = new ToolTraceRecord("root-1", "child-1", "call_1", "web_search", 5L, false, null, null, STARTED);
        assertThat(intake.receive(PROFILE, List.of(child))).isZero();

        when(executions.findRunningAt(eq(PROFILE), eq("root-2"), any(), any(), any()))
            .thenReturn(List.of(execution, mock(AgentExecution.class)));
        ToolTraceRecord twoRuns = new ToolTraceRecord("root-2", "root-2", "call_1", "web_search", 5L, false, null, null, STARTED);
        assertThat(intake.receive(PROFILE, List.of(twoRuns))).isZero();

        ToolTraceRecord noTime = new ToolTraceRecord("root-1", "root-1", "call_1", "web_search", 5L, false, null, null, null);
        assertThat(intake.receive(PROFILE, List.of(noTime))).isZero();

        verify(writer, never()).receive(anyLong(), anyString(), any());
    }


    @Test
    @DisplayName("호출 번호가 있는 기록은 하위 에이전트 session 이어도 호출의 끝을 사건으로 내고, 번호 없는 기록은 내지 않는다")
    void announcesPluginCallEnd() {
        when(bindings.findByProfileNameAndSessionId(PROFILE, "child-1")).thenReturn(Optional.of(mock(HermesSessionBinding.class)));
        ToolTraceRecord child = new ToolTraceRecord("root-1", "child-1", "call_2", "mcp__shop__search", 5L, false, null, null, STARTED);

        intake.receive(PROFILE, List.of(child, record("terminal", "", false, null, null), failed("terminal", "실패", "tool_error")));

        ArgumentCaptor<PluginCallEnded> ended = ArgumentCaptor.forClass(PluginCallEnded.class);
        verify(events, times(2)).publishEvent(ended.capture());
        assertThat(ended.getAllValues()).containsExactly(
            new PluginCallEnded(PROFILE, "root-1", "child-1", "call_2", false),
            new PluginCallEnded(PROFILE, "root-1", "root-1", "call_1", true));
    }


    @Test
    @DisplayName("기록의 문자열 표현에 오류 글 원문이 실리지 않는다")
    void recordToStringHidesErrorMessage() {
        assertThat(failed("terminal", "합성비밀표식", "tool_error").toString()).doesNotContain("합성비밀표식");
    }


    private ToolTrace captured() {
        ArgumentCaptor<ToolTrace> trace = ArgumentCaptor.forClass(ToolTrace.class);
        verify(writer, atLeastOnce()).receive(eq(7L), eq("call_1"), trace.capture());
        return trace.getValue();
    }


    private static ToolTraceRecord failed(String tool, String message, String type) {
        return record(tool, "call_1", true, type, message);
    }


    private static ToolTraceRecord record(String tool, String callId, boolean failed, String type, String message) {
        return new ToolTraceRecord("root-1", "root-1", callId, tool, 1_200L, failed, type, message, STARTED);
    }
}
