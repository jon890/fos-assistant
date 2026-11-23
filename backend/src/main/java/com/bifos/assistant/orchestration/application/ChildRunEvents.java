package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
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
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@link AgentRunner} 가 돌리는 실행의 사건을 남긴다. 시작과 끝은 직접 적고, 그 사이 도구 사건은 Hermes 사건 스트림에서 옮겨 적는다
 * (스펙 assistant-hub REQ3, 대화 turn 의 한 번에 받는 경로와 같다. ADR-090).
 *
 * <p>순서 번호는 실행마다 {@link ChildRunRecording} 하나가 1 부터 센다. 시작, 스트림 사건, 끝의 기록과 번호 증가는 모두 그
 * {@code ChildRunRecording} 을 잠근 채 한다. 끝을 적은 뒤에 온 스트림 사건은 적지도 알리지도 않는다.
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

    private final AgentConnectorBindings connectorBindings;
    private final ExecutionConnectorCalls connectorCalls;
    private final AgentRuntimes runtimes;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final BackgroundTasks backgroundTasks;
    private final ApplicationEventPublisher events;


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
     * 실행 줄에 끝난 상태를 적고, 끝 사건을 남기고, 기록을 닫는다. 열린 스트림은 닫고 그 뒤에 온 스트림 사건은 버린다.
     *
     * <p>끝 상태 쓰기({@code finish})도 잠금 안에서 한다. 스트림 스레드가 같은 실행 줄 객체에 관측 상태를 적어 저장하는 것과 겹치지
     * 않게 하기 위해서다.
     *
     * @param finish 끝난 상태를 실행 줄에 적고 그 줄을 돌려준다
     * @return {@code finish} 가 돌려준 줄
     */
    AgentExecution end(
        ChildRunRecording recording, Supplier<AgentExecution> finish, ExecutionEventType type, String detail) {
        Closeable stream;
        AgentExecution ended;
        synchronized (recording) {
            if (recording.stream != null && !recording.streamEnded) {
                executions.finishEventObservation(recording.execution, recording.terminalSeen);
            }
            ended = finish.get();
            if (!recording.closed) {
                store(recording, sequence -> eventRecorder.record(ended, type, detail, sequence));
            }
            recording.closed = true;
            stream = recording.stream;
        }
        closeQuietly(stream);
        return ended;
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
        AgentExecution execution = recording.execution;
        backgroundTasks.start("child-event-stream-" + runId, () -> {
            boolean[] terminalSeen = {false};
            boolean complete = false;
            try {
                runtimes.of(agent.runtimeTarget()).openEvents(
                    agent.runtimeTarget(),
                    runId,
                    event -> receive(recording, event, conversationId, terminalSeen),
                    stream -> attach(recording, stream),
                    scope);
                complete = terminalSeen[0];
            } catch (RuntimeException ex) {
                if (isClosed(recording)) {
                    // 실행이 끝나 이쪽이 스트림을 닫았다
                    log.debug("맡긴 실행이 끝나 사건 스트림을 닫았다 executionId={}", execution.id());
                } else {
                    log.warn("맡긴 실행의 Hermes 사건 스트림이 끝 상태 전에 끝났다 executionId={} runId={}", execution.id(), runId, ex);
                }
            } finally {
                synchronized (recording) {
                    recording.streamEnded = true;
                    if (!recording.closed) {
                        executions.finishEventObservation(execution, complete);
                    }
                }
            }
        });
    }


    /** 연결 도구의 내용을 통째로 가릴 범위다. 대화 turn 과 같은 값이다({@code ChatRunEvents.toolDetailScope}). */
    ToolDetailScope detailScope(Agent agent, Long treeRootId) {
        return ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id()))
            .withTreeHistory(at -> connectorCalls.calledBefore(treeRootId, at));
    }


    private void receive(ChildRunRecording recording, RunEvent event, Long conversationId, boolean[] terminalSeen) {
        RunEventKind kind = event.kind();
        synchronized (recording) {
            if (recording.closed) {
                return;
            }
            if (kind != null && kind.terminal()) {
                terminalSeen[0] = true;
                recording.terminalSeen = true;
            }
            store(recording, sequence -> eventRecorder.record(recording.execution, event, sequence));
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


    private static boolean isClosed(ChildRunRecording recording) {
        synchronized (recording) {
            return recording.closed;
        }
    }


    private void attach(ChildRunRecording recording, Closeable stream) {
        boolean close;
        synchronized (recording) {
            recording.stream = stream;
            close = recording.closed;
        }
        if (close) {
            closeQuietly(stream);
        }
    }


    /** 사건 하나를 저장하고 번호를 올린다. 잠금 안에서 부른다. 저장하지 못하면 번호를 쓰지 않고 관측을 불완전으로 둔다. */
    private void store(ChildRunRecording recording, IntFunction<ExecutionEvent> build) {
        try {
            ExecutionEvent event = build.apply(recording.next);
            if (event == null) {
                return;
            }
            executionEvents.save(event);
            recording.next++;
        } catch (RuntimeException ex) {
            log.warn("실행 사건을 남기지 못했다 executionId={}", recording.execution.id(), ex);
            executions.finishEventObservation(recording.execution, false);
        }
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
