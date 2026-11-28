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
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
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
import com.bifos.assistant.usage.execution.domain.type.EventObservation;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ChildRunEvents events = new ChildRunEvents(
        bindings, connectorCalls, runtimes, executions, recorder, repository, tasks, publisher, conversations, transactions);
    private final AgentExecution execution = mock(AgentExecution.class);
    private final Agent agent = mock(Agent.class);
    private final Conversation conversation = mock(Conversation.class);

    /** 기록기가 받은 순서 번호다. 시작과 끝은 이름으로, 스트림 사건은 사건 종류로 적는다. */
    private final List<String> recorded = new ArrayList<>();
    /** 테스트가 스트림 사건을 흘려 넣는 자리다. */
    private Consumer<RunEvent> onEvent;
    private final Closeable opened = mock(Closeable.class);
    private final CountDownLatch streamOpened = new CountDownLatch(1);
    private final CountDownLatch streamReleased = new CountDownLatch(1);
    private final CountDownLatch streamReadInterrupted = new CountDownLatch(1);
    private final CountDownLatch streamClosed = new CountDownLatch(1);
    private final List<Thread> workers = new ArrayList<>();


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        when(execution.id()).thenReturn(11L);
        when(execution.treeRootId()).thenReturn(10L);
        when(execution.conversationId()).thenReturn(7L);
        when(execution.eventObservation()).thenReturn(EventObservation.OBSERVING);
        when(agent.id()).thenReturn(5L);
        when(agent.runtimeTarget()).thenReturn(target);
        when(runtimes.of(target)).thenReturn(stream);
        when(conversation.id()).thenReturn(7L);
        when(conversations.findByIdForShare(7L)).thenReturn(Optional.of(conversation));
        when(transactions.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
        when(bindings.connectorToolPrefixes(5L)).thenReturn(Set.of("mcp_naver_shopping_"));
        when(recorder.record(any(AgentExecution.class), any(ExecutionEventType.class), any(), anyInt()))
            .thenAnswer(call -> recorded(call.getArgument(1).toString(), call.getArgument(3)));
        when(recorder.record(any(AgentExecution.class), any(RunEvent.class), anyInt()))
            .thenAnswer(call -> recorded(((RunEvent)call.getArgument(1)).kind().name(), call.getArgument(2)));
        // 스트림을 연 뒤 테스트가 사건과 종료 순서를 정한다.
        when(tasks.start(anyString(), any())).thenAnswer(call -> {
            Runnable task = call.getArgument(1);
            Thread thread = new Thread(task);
            workers.add(thread);
            thread.start();
            return thread;
        });
        doAnswer(call -> {
            streamClosed.countDown();
            streamReleased.countDown();
            return null;
        }).when(opened).close();
        doAnswer(call -> {
            onEvent = call.getArgument(2);
            ((Consumer<Closeable>)call.getArgument(3)).accept(opened);
            streamOpened.countDown();
            try {
                assertThat(streamReleased.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                streamReadInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(stream).openEvents(any(), anyString(), any(), any(), any(ToolDetailScope.class));
    }


    @AfterEach
    void tearDown() throws InterruptedException {
        releaseStream();
    }


    @Test
    @DisplayName("런타임이 호출 번호를 재사용해도 새로 시작한 도구의 완료를 기록한다")
    void recordsAnotherToolCallWithReusedId() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();
        RunEvent started = toolCall(RunEventKind.TOOL_STARTED, "call-1");
        RunEvent completed = toolCall(RunEventKind.TOOL_COMPLETED, "call-1");
        onEvent.accept(started);
        onEvent.accept(completed);
        onEvent.accept(completed);
        onEvent.accept(started);
        onEvent.accept(completed);
        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);
        releaseStream();

        assertThat(recorded)
            .containsExactly("TOOL_STARTED#1", "TOOL_COMPLETED#2", "TOOL_STARTED#3", "TOOL_COMPLETED#4", "RUN_COMPLETED#5");
        verify(publisher, times(2)).publishEvent(new DelegatedRunActivity(7L, 10L, 11L, completed));
    }


    @Test
    @DisplayName("자식의 결과를 먼저 확정해도 같은 호출의 늦은 도구 완료를 한 번만 기록하고 알린다")
    void recordsLateToolCompletionOnce() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();
        RunEvent started = toolCall(RunEventKind.TOOL_STARTED, "call-1");
        RunEvent completed = toolCall(RunEventKind.TOOL_COMPLETED, "call-1");
        onEvent.accept(started);

        try {
            assertThat(events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null))
                .isSameAs(execution);
            verify(opened, never()).close();
            onEvent.accept(completed);
            onEvent.accept(completed);
            onEvent.accept(toolCall(RunEventKind.RUN_COMPLETED, null));
        } finally {
            releaseStream();
        }

        assertThat(recorded).containsExactly("TOOL_STARTED#1", "RUN_COMPLETED#2", "TOOL_COMPLETED#3");
        verify(publisher).publishEvent(new DelegatedRunActivity(7L, 10L, 11L, completed));
        verify(executions).completeLateEventObservation(11L);
    }


    @Test
    @DisplayName("시간 상한 뒤 스트림을 닫고 늦은 호출은 기록하거나 알리지 않는다")
    void closesStreamWhenCollectionTimesOut() throws Exception {
        doAnswer(call -> {
            // HTTP 본문을 읽는 쪽이 먼저 풀려야 닫히는 연결을 흉내 낸다.
            assertThat(streamReadInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            streamClosed.countDown();
            return null;
        }).when(opened).close();
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();
        onEvent.accept(toolCall(RunEventKind.TOOL_STARTED, "call-1"));

        assertThat(events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null)).isSameAs(execution);
        assertThat(streamClosed.await(5, TimeUnit.SECONDS)).isTrue();
        RunEvent completed = toolCall(RunEventKind.TOOL_COMPLETED, "call-1");
        onEvent.accept(completed);

        assertThat(recorded).containsExactly("TOOL_STARTED#1", "RUN_COMPLETED#2");
        verify(publisher, never()).publishEvent(new DelegatedRunActivity(7L, 10L, 11L, completed));
        verify(executions, never()).completeLateEventObservation(11L);
    }


    @Test
    @DisplayName("이미 끝난 스트림을 결과 확정 뒤 다시 열거나 늦은 수집을 시작하지 않는다")
    void doesNotDrainAnEndedStream() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();
        onEvent.accept(toolCall(RunEventKind.TOOL_STARTED, "call-1"));
        releaseStream();

        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);
        onEvent.accept(toolCall(RunEventKind.TOOL_COMPLETED, "call-1"));

        assertThat(recorded).containsExactly("TOOL_STARTED#1", "RUN_COMPLETED#2");
        verify(executions).finishEventObservation(execution, false);
        verify(tasks, times(1)).start(anyString(), any());
    }


    @Test
    @DisplayName("대화가 삭제됐으면 늦은 도구 완료 내용을 저장하거나 알리지 않는다")
    void discardsLateToolCompletionForDeletedConversation() throws Exception {
        ChildRunRecording recording = events.start(execution);
        events.relay(recording, agent, "run-1", conversation);
        awaitStreamOpened();
        onEvent.accept(toolCall(RunEventKind.TOOL_STARTED, "call-1"));

        events.end(recording, () -> execution, ExecutionEventType.RUN_COMPLETED, null);
        when(conversation.deletedAt()).thenReturn(Instant.now());
        RunEvent completed = toolCall(RunEventKind.TOOL_COMPLETED, "call-1");
        onEvent.accept(completed);
        onEvent.accept(toolCall(RunEventKind.RUN_COMPLETED, null));
        releaseStream();

        verify(repository, times(2)).save(any());
        verify(publisher, never()).publishEvent(new DelegatedRunActivity(7L, 10L, 11L, completed));
        verify(executions, never()).completeLateEventObservation(11L);
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
        verify(recorder, times(4)).saved(any(ExecutionEvent.class));
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues())
            .allSatisfy(value -> assertThat(((DelegatedRunActivity)value).executionId()).isEqualTo(11L));
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


    private static RunEvent toolCall(RunEventKind kind, String toolCallId) {
        return new RunEvent(kind, null, "example_tool", null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, toolCallId);
    }


    private void awaitStreamOpened() throws InterruptedException {
        assertThat(streamOpened.await(5, TimeUnit.SECONDS)).as("스트림이 열렸다").isTrue();
    }


    private void releaseStream() throws InterruptedException {
        streamReleased.countDown();
        for (Thread worker : workers) {
            assertThat(worker.join(Duration.ofSeconds(5))).isTrue();
        }
    }
}
