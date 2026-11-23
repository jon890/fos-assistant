package com.bifos.assistant.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.nextturn.application.dto.DelegatedRunActivity;
import com.bifos.assistant.runtime.application.AgentRuntimes;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.runtime.application.port.AgentRuntime;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.usage.execution.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.execution.application.ExecutionRecorder;
import com.bifos.assistant.usage.execution.application.port.ExecutionConnectorCalls;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ChildRunEventsTest {
    private final AgentConnectorBindings bindings = mock(AgentConnectorBindings.class);
    private final ExecutionConnectorCalls connectorCalls = mock(ExecutionConnectorCalls.class);
    private final AgentRuntimes runtimes = mock(AgentRuntimes.class);
    private final AgentRuntime stream = mock(AgentRuntime.class);
    private final RuntimeTarget target = mock(RuntimeTarget.class);
    private final ExecutionRecorder executions = mock(ExecutionRecorder.class);
    private final ExecutionEventRecorder recorder = mock(ExecutionEventRecorder.class);
    private final ExecutionEventRepository repository = mock(ExecutionEventRepository.class);
    private final BackgroundTasks tasks = mock(BackgroundTasks.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ChildRunEvents events = new ChildRunEvents(
        bindings, connectorCalls, runtimes, executions, recorder, repository, tasks, publisher);
    private final AgentExecution execution = mock(AgentExecution.class);
    private final Agent agent = mock(Agent.class);
    private final Conversation conversation = mock(Conversation.class);

    /** 기록기가 받은 순서 번호다. 시작과 끝은 이름으로, 스트림 사건은 사건 종류로 적는다. */
    private final List<String> recorded = new ArrayList<>();
    /** 테스트가 스트림 사건을 흘려 넣는 자리다. */
    private Consumer<RunEvent> onEvent;
    private final Closeable opened = mock(Closeable.class);


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(execution.id()).thenReturn(11L);
        when(execution.treeRootId()).thenReturn(10L);
        when(agent.id()).thenReturn(5L);
        when(agent.runtimeTarget()).thenReturn(target);
        when(runtimes.of(target)).thenReturn(stream);
        when(conversation.id()).thenReturn(7L);
        when(bindings.connectorToolPrefixes(5L)).thenReturn(Set.of("mcp_naver_shopping_"));
        when(recorder.record(any(AgentExecution.class), any(ExecutionEventType.class), any(), anyInt()))
            .thenAnswer(call -> recorded(call.getArgument(1).toString(), call.getArgument(3)));
        when(recorder.record(any(AgentExecution.class), any(RunEvent.class), anyInt()))
            .thenAnswer(call -> recorded(((RunEvent)call.getArgument(1)).kind().name(), call.getArgument(2)));
        // 스트림 스레드는 이 테스트에서 따로 띄우지 않는다. 시작한 작업을 붙잡아 두고 사건은 onEvent 로 흘린다.
        when(tasks.start(anyString(), any())).thenAnswer(call -> {
            Runnable task = call.getArgument(1);
            Thread thread = new Thread(task);
            thread.start();
            return thread;
        });
        doAnswer(call -> {
            onEvent = call.getArgument(2);
            ((Consumer<Closeable>)call.getArgument(3)).accept(opened);
            synchronized (this) {
                notifyAll();
                wait(5_000);
            }
            return null;
        }).when(stream).openEvents(any(), anyString(), any(), any(), any(ToolDetailScope.class));
    }


    @Test
    @DisplayName("시작, 도구 사건 둘, 끝이 1 부터 빈틈없는 번호로 적히고 도구 사건마다 화면 알림이 나간다")
    void recordsStartToolsAndEndInGaplessOrder() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.append(recording, ExecutionEventType.RUN_STARTED, null);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();

        onEvent.accept(tool(RunEventKind.TOOL_STARTED, "web_search", "운동화", null, null));
        onEvent.accept(tool(RunEventKind.TOOL_COMPLETED, "web_search", "운동화", 300L, false));
        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);
        onEvent.accept(tool(RunEventKind.TOOL_STARTED, "web_extract", "늦은 사건", null, null));
        releaseStream();

        assertThat(recorded).containsExactly("RUN_STARTED#1", "TOOL_STARTED#2", "TOOL_COMPLETED#3", "RUN_COMPLETED#4");
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues())
            .allSatisfy(value -> assertThat(((DelegatedRunActivity)value).executionId()).isEqualTo(11L));
        verify(opened).close();
    }


    @Test
    @DisplayName("스트림 열기에 넘긴 범위가 그 에이전트의 연결 도구 내용을 가린다")
    void passesScopeThatHidesConnectorTools() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();

        ArgumentCaptor<ToolDetailScope> scope = ArgumentCaptor.forClass(ToolDetailScope.class);
        verify(stream).openEvents(eq(target), eq("run-1"), any(), any(), scope.capture());
        assertThat(scope.getValue().hides("mcp_naver_shopping_search")).isTrue();
        assertThat(scope.getValue().hides("web_search")).isFalse();
        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);
        releaseStream();
    }


    @Test
    @DisplayName("대화가 없는 실행은 기록만 하고 화면에 알리지 않는다")
    void doesNotPublishWithoutConversation() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", null);
        awaitStreamOpened();

        onEvent.accept(tool(RunEventKind.TOOL_STARTED, "web_search", "운동화", null, null));
        events.end(recording, () -> execution, ExecutionEventType.RUN_FAILED, "X");
        releaseStream();

        assertThat(recorded).containsExactly("TOOL_STARTED#1", "RUN_FAILED#2");
        verify(publisher, never()).publishEvent(any());
    }


    @Test
    @DisplayName("스트림이 예외로 끝나도 끝 사건은 적힌다")
    void recordsEndEvenWhenStreamFails() {
        doAnswer(call -> {
            throw new IllegalStateException("끊김");
        }).when(stream).openEvents(any(), anyString(), any(), any(), any(ToolDetailScope.class));
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);

        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);

        assertThat(recorded).containsExactly("RUN_COMPLETED#1");
    }


    @Test
    @DisplayName("가리기 범위 조회가 실패해도 예외를 던지지 않고 관측만 불완전으로 남긴 채 끝 사건을 적는다")
    void scopeLookupFailureDoesNotFailRun() {
        when(bindings.connectorToolPrefixes(5L)).thenThrow(new IllegalStateException("DB 실패"));
        ChildRunRecording recording = events.start(execution);

        events.relay(recording, agent, "run-1", conversation);
        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);

        verify(executions).finishEventObservation(execution, false);
        verify(stream, never()).openEvents(any(), anyString(), any(), any(), any(ToolDetailScope.class));
        assertThat(recorded).containsExactly("RUN_COMPLETED#1");
    }


    private static RunEvent tool(RunEventKind kind, String name, String detail, Long durationMs, Boolean failed) {
        return new RunEvent(kind, null, name, detail, durationMs, failed, null, null, null, null, null, null, null, null,
            null, null, null);
    }


    private ExecutionEvent recorded(String name, int sequence) {
        recorded.add(name + "#" + sequence);
        return mock(ExecutionEvent.class);
    }


    private void awaitStreamOpened() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        synchronized (this) {
            while (onEvent == null && System.currentTimeMillis() < deadline) {
                wait(50);
            }
        }
        assertThat(onEvent).as("스트림이 열렸다").isNotNull();
    }


    private void releaseStream() {
        synchronized (this) {
            notifyAll();
        }
    }
}
