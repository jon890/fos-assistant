package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegatedRunActivity;
import com.bifos.assistant.runtime.application.AgentRuntimes;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
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
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AgentRunner} 가 돌리는 실행의 사건을 남긴다. 시작과 끝은 직접 적고, 그 사이 도구 사건은 Hermes 사건 스트림에서 옮겨 적는다
 * (스펙 assistant-hub REQ3, 대화 turn 의 한 번에 받는 경로와 같다. ADR-090).
 *
 * <p>순서 번호는 실행마다 {@link ChildRunRecording} 하나가 1 부터 센다. 시작, 스트림 사건, 끝의 기록과 번호 증가는 모두 그
 * {@code ChildRunRecording} 을 잠근 채 한다. 성공을 먼저 기록했으면 짧은 시간 동안 앞서 적은 도구 시작의 완료만 받는다.
 *
 * <p>연결 도구의 내용은 스트림을 열 때 넘기는 {@link ToolDetailScope} 가 가린다(ADR-047, ADR-083). 도구 사건은 대화 화면의 도는 답에
 * 그리도록 {@link DelegatedRunActivity} 로도 알린다. 받는 쪽이 직접 위임인지 가른다.
 *
 * <p>사건은 관측용이다. 저장이나 스트림이 실패해도 실행은 그대로 이어지고 관측 상태만 불완전으로 남는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChildRunEvents {

    private static final Duration LATE_TOOL_WINDOW = Duration.ofSeconds(1);

    private final AgentConnectorBindings connectorBindings;
    private final ExecutionConnectorCalls connectorCalls;
    private final AgentRuntimes runtimes;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final BackgroundTasks backgroundTasks;
    private final ApplicationEventPublisher events;
    private final ConversationRepository conversations;
    private final TransactionTemplate transactions;


    /** 실행 하나의 사건 기록을 시작한다. 관측 시작을 실행 줄에 적는다. 제출 전에 부른다. */
    ChildRunRecording start(AgentExecution execution) {
        executions.beginEventObservation(execution);
        return new ChildRunRecording(execution);
    }


    /** 실행 줄만 만든 채 제출 전에 끝나는 경로의 기록이다. 관측 상태를 바꾸지 않는다. */
    ChildRunRecording unobserved(AgentExecution execution) {
        return new ChildRunRecording(execution);
    }


    /** 시작이나 다른 Control Plane 사건을 적는다. 끝난 뒤에는 적지 않는다. */
    void append(ChildRunRecording recording, ExecutionEventType type, String detail) {
        synchronized (recording) {
            if (!recording.closed) {
                store(recording, sequence -> eventRecorder.record(recording.execution, type, detail, sequence));
            }
        }
    }


    /**
     * 실행 줄과 끝 사건을 먼저 적는다. 성공 결과를 돌려주는 동안 남은 도구 완료는 스트림 스레드가 받는다.
     *
     * <p>끝 상태 쓰기({@code finish})도 잠금 안에서 한다. 스트림 스레드가 같은 실행 줄 객체에 관측 상태를 적어 저장하는 것과 겹치지
     * 않게 하기 위해서다.
     *
     * @param finish 끝난 상태를 실행 줄에 적고 그 줄을 돌려준다
     * @return {@code finish} 가 돌려준 줄
     */
    AgentExecution end(
        ChildRunRecording recording, Supplier<AgentExecution> finish, ExecutionEventType type, String detail) {
        Thread reader;
        Closeable streamToClose;
        Long deadline;
        AgentExecution ended;
        synchronized (recording) {
            reader = recording.streamEnded ? null : recording.streamThread;
            boolean drain = !recording.closed && reader != null && type == ExecutionEventType.RUN_COMPLETED;
            if (drain) {
                recording.drainIncomplete = recording.drainIncomplete
                    || recording.execution.eventObservation() != EventObservation.OBSERVING;
            } else if (!recording.closed && reader != null) {
                executions.finishEventObservation(recording.execution, hasAllEvents(recording));
            }
            ended = finish.get();
            if (!recording.closed) {
                store(recording, sequence -> eventRecorder.record(ended, type, detail, sequence));
            }
            recording.closed = true;
            deadline = drain ? System.nanoTime() + LATE_TOOL_WINDOW.toNanos() : null;
            recording.drainDeadline = deadline;
            streamToClose = !drain && reader != null ? recording.stream : null;
        }
        if (deadline != null) {
            limitDrain(recording, reader, deadline);
        } else {
            closeQuietly(streamToClose);
        }
        return ended;
    }


    private void limitDrain(ChildRunRecording recording, Thread reader, long deadline) {
        try {
            backgroundTasks.start("child-event-drain-" + recording.execution.id(), () -> {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0 && reader.join(Duration.ofNanos(remaining))) {
                        return;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                expireDrain(recording, reader);
            });
        } catch (RuntimeException ex) {
            log.warn("맡긴 실행의 사건 수집 상한을 시작하지 못했다 executionId={}", recording.execution.id(), ex);
            expireDrain(recording, reader);
        }
    }


    private static void expireDrain(ChildRunRecording recording, Thread reader) {
        Closeable stream;
        synchronized (recording) {
            if (recording.streamEnded || recording.drainDeadline == null) {
                return;
            }
            recording.drainDeadline = null;
            stream = recording.stream;
        }
        // 응답이 멈춘 HTTP 연결은 close 만 부르면 읽기가 풀릴 때까지 기다릴 수 있다.
        reader.interrupt();
        closeQuietly(stream);
    }


    /**
     * 사건 스트림을 다른 스레드에서 읽기 시작하고 곧바로 돌아온다. 실행의 끝은 부르는 쪽의 결과 조회가 정한다.
     *
     * @param conversation 그 실행의 대화. 없으면 화면에 알리지 않는다
     */
    void relay(ChildRunRecording recording, Agent agent, String runId, Conversation conversation) {
        AgentExecution execution = recording.execution;
        Long conversationId = conversation == null ? null : conversation.id();
        // 가리기 범위 조회나 스레드 시작이 실패해도 제출한 실행은 그대로 둔다. 도구 사건만 없고 관측은 불완전으로 남는다
        try {
            ToolDetailScope scope = detailScope(agent, execution.treeRootId());
            startStream(recording, agent, runId, conversationId, scope);
        } catch (RuntimeException ex) {
            log.warn("맡긴 실행의 사건 스트림을 시작하지 못했다 executionId={} runId={}", execution.id(), runId, ex);
            synchronized (recording) {
                recording.streamEnded = true;
                if (!recording.closed) {
                    executions.finishEventObservation(execution, false);
                }
            }
        }
    }


    private void startStream(
        ChildRunRecording recording, Agent agent, String runId, Long conversationId, ToolDetailScope scope) {
        synchronized (recording) {
            recording.streamThread = backgroundTasks.start("child-event-stream-" + runId,
                () -> readStream(recording, agent, runId, conversationId, scope));
        }
    }


    private void readStream(
        ChildRunRecording recording, Agent agent, String runId, Long conversationId, ToolDetailScope scope) {
        boolean returned = false;
        try {
            runtimes.of(agent.runtimeTarget()).openEvents(
                agent.runtimeTarget(), runId, event -> receive(recording, event, conversationId),
                stream -> attach(recording, stream), scope);
            returned = true;
        } catch (RuntimeException ex) {
            if (acceptsEvents(recording)) {
                log.warn("맡긴 실행의 Hermes 사건 스트림이 끊겼다 executionId={} runId={}",
                    recording.execution.id(), runId, ex);
            }
        } finally {
            endStream(recording, returned);
        }
    }


    private void endStream(ChildRunRecording recording, boolean returned) {
        boolean completeLate;
        synchronized (recording) {
            boolean complete = returned && hasAllEvents(recording);
            completeLate = complete && !recording.drainIncomplete && isDraining(recording);
            recording.streamEnded = true;
            recording.drainDeadline = null;
            if (!recording.closed) {
                executions.finishEventObservation(recording.execution, complete);
            }
        }
        if (completeLate) {
            try {
                transactions.execute(status -> executions.completeLateEventObservation(recording.execution.id()));
            } catch (RuntimeException ex) {
                log.warn("맡긴 실행의 후속 사건 관측 상태를 저장하지 못했다 executionId={}", recording.execution.id(), ex);
            }
        }
    }


    private static boolean hasAllEvents(ChildRunRecording recording) {
        return recording.terminalSeen && recording.pendingTools.values().stream().allMatch(remaining -> remaining == 0);
    }


    /** 연결 도구의 내용을 통째로 가릴 범위다. 대화 turn 과 같은 값이다({@code ChatRunEvents.toolDetailScope}). */
    ToolDetailScope detailScope(Agent agent, Long treeRootId) {
        return ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id()))
            .withTreeHistory(at -> connectorCalls.calledBefore(treeRootId, at));
    }


    private void receive(ChildRunRecording recording, RunEvent event, Long conversationId) {
        RunEventKind kind = event.kind();
        String callId = event.toolCallId();
        synchronized (recording) {
            if (!acceptsEvents(recording)) {
                return;
            }
            if (kind != null && kind.terminal()) {
                recording.terminalSeen = true;
                return;
            }
            Integer pending = recording.pendingTools.get(callId);
            if (kind == RunEventKind.TOOL_COMPLETED && pending != null && pending == 0) {
                return;
            }
            if (recording.closed && !acceptsLateTool(recording, kind, pending)) {
                return;
            }
            boolean stored = store(recording, sequence -> eventRecorder.record(recording.execution, event, sequence));
            if (stored && callId != null && !callId.isBlank() && callId.length() <= ExecutionEvent.TOOL_CALL_ID_LIMIT) {
                if (kind == RunEventKind.TOOL_STARTED) {
                    recording.pendingTools.merge(callId, 1, Integer::sum);
                } else if (kind == RunEventKind.TOOL_COMPLETED) {
                    recording.pendingTools.put(callId, pending == null ? 0 : pending - 1);
                }
            }
            if (recording.closed && !stored) {
                return;
            }
        }
        if (conversationId != null && (kind == RunEventKind.TOOL_STARTED || kind == RunEventKind.TOOL_COMPLETED)) {
            AgentExecution execution = recording.execution;
            try {
                events.publishEvent(new DelegatedRunActivity(conversationId, execution.treeRootId(), execution.id(), event));
            } catch (RuntimeException ex) {
                log.warn("맡긴 실행의 도구 사건을 화면에 알리지 못했다 executionId={}", execution.id(), ex);
            }
        }
    }


    private static boolean acceptsLateTool(ChildRunRecording recording, RunEventKind kind, Integer pending) {
        // 호출 번호를 재사용하는 런타임에서 앞서 기록한 시작을 하나로 특정할 수 있어야 한다.
        if (kind == RunEventKind.TOOL_COMPLETED && pending != null && pending == 1) {
            return true;
        }
        if (kind == RunEventKind.TOOL_STARTED || kind == RunEventKind.TOOL_COMPLETED
            || kind == RunEventKind.SUBAGENT_STARTED || kind == RunEventKind.SUBAGENT_COMPLETED) {
            recording.drainIncomplete = true;
        }
        return false;
    }


    private static boolean acceptsEvents(ChildRunRecording recording) {
        synchronized (recording) {
            return !recording.streamEnded && (!recording.closed || isDraining(recording));
        }
    }


    private static boolean isDraining(ChildRunRecording recording) {
        return recording.drainDeadline != null && recording.drainDeadline - System.nanoTime() > 0;
    }


    private void attach(ChildRunRecording recording, Closeable stream) {
        boolean close;
        synchronized (recording) {
            recording.stream = stream;
            close = !acceptsEvents(recording);
        }
        if (close) {
            closeQuietly(stream);
        }
    }


    /** 사건 하나를 저장하고 번호를 올린다. 잠금 안에서 부른다. 저장하지 못하면 번호를 쓰지 않고 관측을 불완전으로 둔다. */
    private boolean store(ChildRunRecording recording, IntFunction<ExecutionEvent> build) {
        boolean stored = false;
        try {
            ExecutionEvent event = build.apply(recording.next);
            if (event == null) {
                return false;
            }
            Long conversationId = recording.execution.conversationId();
            if (recording.closed && conversationId != null) {
                if (!saveBeforePurge(recording, conversationId, event)) {
                    return false;
                }
            } else {
                executionEvents.save(event);
            }
            recording.next++;
            stored = true;
            eventRecorder.saved(event);
        } catch (RuntimeException ex) {
            log.warn("실행 사건을 남기지 못했다 executionId={}", recording.execution.id(), ex);
            recording.drainIncomplete = true;
            if (!recording.closed) {
                executions.finishEventObservation(recording.execution, false);
            }
        }
        return stored;
    }


    /** 대화 삭제 및 본문 정리와 같은 줄을 잠근다. 삭제가 먼저면 도구 결과를 다시 남기지 않는다. */
    private boolean saveBeforePurge(ChildRunRecording recording, Long conversationId, ExecutionEvent event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (conversations.findByIdForShare(conversationId).filter(row -> row.deletedAt() == null).isEmpty()
                || !isDraining(recording)) {
                return false;
            }
            executionEvents.save(event);
            return true;
        }));
    }


    private static void closeQuietly(Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException | RuntimeException ex) {
            log.debug("맡긴 실행의 사건 스트림을 닫지 못했다", ex);
        }
    }
}
