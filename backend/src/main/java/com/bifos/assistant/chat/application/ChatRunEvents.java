package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ToolDetailScope;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Hermes 실행을 제출하고 사건 스트림과 최종 결과를 읽는다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatRunEvents {
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("run.completed", "run.failed", "run.cancelled", "run.interrupted");

    private final AgentConnectorBindings connectorBindings;
    private final HermesRunsClient hermes;
    private final HermesRunEventStream eventStream;
    private final LiveProperties<HermesProperties> hermesProperties;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final TurnCancellation turns;
    private final UserExecutionLimiter limiter;
    private final BackgroundTasks backgroundTasks;

    String submit(PendingTurn pending) {
        try {
            executions.markSubmitted(pending.execution());
            executions.beginEventObservation(pending.execution());
            String runId = hermes.submit(pending.command());
            executions.attachRunId(pending.execution(), runId);
            append(pending, ExecutionEventType.RUN_STARTED, null);
            turns.trackRun(
                    pending.execution().id(),
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId);
            return runId;
        } catch (ApiException ex) {
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    /**
     * Hermes 사건 스트림이 닫히거나 중지 유예가 끝날 때까지 읽는다.
     *
     * @param onEvent 사건을 화면으로 흘릴 곳. null 이면 한 번에 받는 경로라 실행 기록에만 남기고 답 조각 시각도 적지 않는다.
     *     이 경로는 스트림을 기다리는 시간에도 {@code hermes.run-timeout} 상한을 두고, 넘으면 스트림을 닫은 채 결과 조회로 넘어간다
     */
    void relay(PendingTurn pending, String runId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        // 일부 HTTP 스트림은 다른 스레드의 close 중에도 readLine 을 놓지 않는다.
        // 중지 유예 시간이 지나면 요청 스레드를 먼저 풀어 상태 조회와 stopped 사건으로 진행한다.
        CompletableFuture<Void> streamDone = new CompletableFuture<>();
        AtomicBoolean terminalSeen = new AtomicBoolean();
        ToolDetailScope detailScope = toolDetailScope(pending.agent());
        backgroundTasks.start("turn-event-stream-" + runId, () -> {
            try {
                eventStream.open(
                        pending.command().apiBaseUrl(),
                        pending.command().profileName(),
                        runId,
                        event -> {
                            synchronized (pending) {
                                if (!handle.cancelled().get() || !turns.isStopConfirmed(handle)) {
                                    if (TERMINAL_EVENTS.contains(event.type())) {
                                        terminalSeen.set(true);
                                    }
                                    if (onEvent == null) {
                                        append(pending, event);
                                    } else {
                                        forward(pending, event, onEvent);
                                    }
                                }
                            }
                        },
                        stream -> turns.attachStream(handle, stream),
                        detailScope);
            } catch (ApiException ex) {
                synchronized (pending) {
                    executions.finishEventObservation(pending.execution(), false);
                }
                log.warn("Hermes event stream ended before final status runId={}", runId, ex);
            } catch (RuntimeException ex) {
                synchronized (pending) {
                    executions.finishEventObservation(pending.execution(), false);
                }
                // 사건은 관측용이다. 읽다가 예기치 못한 오류가 나도 가려진 스레드 오류로 두지 않고 남긴다
                log.warn("Hermes event stream failed runId={}", runId, ex);
            } finally {
                synchronized (pending) {
                    executions.finishEventObservation(pending.execution(), terminalSeen.get());
                }
                turns.detachStream(handle);
                streamDone.complete(null);
            }
        });
        if (onEvent == null) {
            turns.awaitStreamOrGrace(
                    handle, streamDone, hermesProperties.current().runTimeout());
        } else {
            turns.awaitStreamOrGrace(handle, streamDone);
        }
        synchronized (pending) {
            if (!streamDone.isDone()) {
                executions.finishEventObservation(pending.execution(), false);
            }
        }
    }

    /**
     * 실행 기록에서 내용을 통째로 가릴 도구다. 옛 커넥터 에이전트는 모두 가리고, 다른 에이전트는 붙은 커넥터 서버의 도구만
     * 가린다(ADR-083). 외부 서비스의 글이 실행 기록에 남지 않게 한다.
     */
    ToolDetailScope toolDetailScope(Agent agent) {
        if (agent.connectorManaged()) {
            return ToolDetailScope.ALL;
        }
        return ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id()));
    }

    HermesRunResult awaitCompletion(PendingTurn pending, String runId) {
        try {
            return hermes.awaitCompletion(pending.command(), runId);
        } catch (ApiException ex) {
            // Hermes 에서 끝났는지 모르는 run 은 끝날 때까지 사용자 자리를 쥔다(ADR-069).
            limiter.holdUntilRemoteEnds(
                    pending.user().id(),
                    pending.execution().id(),
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId,
                    false);
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    /** 스트림으로 온 사건을 화면으로 중계하면서 우리 이름으로도 옮겨 적는다. */
    void forward(PendingTurn pending, RunEvent event, Consumer<ChatEvent> onEvent) {
        append(pending, event);
        String type = event.type() == null ? "" : event.type().toLowerCase();
        CheckTurn check = pending.intent() instanceof TurnIntent.ProactiveCheck proactive ? proactive.check() : null;
        if ("message.delta".equals(type) && event.text() != null) {
            executions.markFirstDelta(pending.execution());
            synchronized (pending) {
                pending.streamed().append(event.text());
                // 살펴보기의 답은 결과 블록의 JSON 이 섞인 글이라 흘리지 않는다. 검사해 그린 글만 남긴다.
                if (check == null) {
                    onEvent.accept(ChatEvent.delta(event.text()));
                }
            }
        } else if ("tool.started".equals(type)) {
            onEvent.accept(ChatEvent.tool(event.toolName(), event.detail(), ChatEvent.STARTED, null, null));
            if (check != null) {
                check.toolStarted(pending.execution().id());
            }
        } else if ("tool.completed".equals(type)) {
            onEvent.accept(ChatEvent.tool(
                    event.toolName(), event.detail(), ChatEvent.COMPLETED, event.durationMs(), event.failed()));
        } else if ("subagent.start".equals(type)) {
            onEvent.accept(ChatEvent.subagent(
                    event.subagentId(),
                    event.goal() == null ? event.detail() : event.goal(),
                    event.model(),
                    ChatEvent.STARTED,
                    null,
                    null,
                    null,
                    null));
        } else if ("subagent.complete".equals(type)) {
            onEvent.accept(ChatEvent.subagent(
                    event.subagentId(),
                    event.goal() == null ? event.detail() : event.goal(),
                    event.model(),
                    ChatEvent.COMPLETED,
                    event.inputTokens(),
                    event.outputTokens(),
                    event.durationMs(),
                    event.failed()));
        }
    }

    void append(PendingTurn pending, ExecutionEventType type, String detail) {
        store(pending, sequence -> eventRecorder.record(pending.execution(), type, detail, sequence));
    }

    void append(PendingTurn pending, RunEvent event) {
        store(pending, sequence -> eventRecorder.record(pending.execution(), event, sequence));
    }

    /**
     * 사건 하나를 저장한다.
     *
     * <p>저장이 실패해도 중계와 대화는 그대로 이어진다. 사건은 관측용이고 그것 때문에 답이 끊기면 안
     * 된다. 옮겨 적지 못한 사건과 저장에 실패한 사건은 순서를 소비하지 않아, 번호가 1부터 빈틈없이
     * 이어진다.
     */
    void store(PendingTurn pending, IntFunction<ExecutionEvent> build) {
        try {
            ExecutionEvent event = build.apply(pending.counter().peek());
            if (event == null) {
                return;
            }
            executionEvents.save(event);
            pending.counter().advance();
        } catch (RuntimeException ex) {
            log.warn(
                    "could not record an execution event executionId={}",
                    pending.execution().id(),
                    ex);
            executions.finishEventObservation(pending.execution(), false);
        }
    }
}
