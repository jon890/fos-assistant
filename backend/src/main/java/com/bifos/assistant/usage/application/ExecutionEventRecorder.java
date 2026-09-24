package com.bifos.assistant.usage.application;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hermes 사건을 우리 이름으로 옮겨 적는다.
 *
 * <p>Hermes 의 사건 이름을 아는 곳은 여기 하나다. 이름이 바뀌면 이 클래스만 고친다. 근거는 ADR-013 에
 * 있다.
 *
 * <p>여기가 옮겨 적는 것은 {@code tool.} 과 {@code subagent.} 계열 넷뿐이다. 실행의 시작과 끝은
 * {@code ChatService} 가 직접 적는다. 스트림을 열지 않는 경로에도 그것이 남아야 하고, 양쪽에서 적으면
 * 스트리밍 경로에만 같은 사건이 두 줄 남는다.
 *
 * <p>이 클래스는 저장하지 않고 엔티티를 만들기만 한다. 저장을 부르는 쪽이 하면 저장 실패를 감싸는
 * 자리가 한 곳으로 모인다.
 */
@Service
public class ExecutionEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventRecorder.class);

    /**
     * Hermes 이름에서 우리 이름으로 가는 표다.
     *
     * <p>Hermes v0.21.0 의 API server 가 실제로 보내는 이름이다. 하위 에이전트 사건의 어미가 도구
     * 사건과 다르다. {@code subagent.start} 는 {@code .started} 가 아니고 {@code subagent.complete} 는
     * {@code .completed} 가 아니다.
     *
     * <p>여기 없는 이름은 저장하지 않는다. {@code message.delta} 와 {@code reasoning.available} 과
     * {@code run.completed} 가 그렇다.
     */
    private static final Map<String, ExecutionEventType> OUR_NAMES = Map.of(
            "tool.started", ExecutionEventType.TOOL_STARTED,
            "tool.completed", ExecutionEventType.TOOL_COMPLETED,
            "subagent.start", ExecutionEventType.SUBAGENT_STARTED,
            "subagent.complete", ExecutionEventType.SUBAGENT_COMPLETED);

    /**
     * Hermes 사건 하나를 우리 사건으로 옮긴다.
     *
     * <p>옮길 수 없는 사건은 저장하지 않고 {@code null} 을 낸다. 버린 이름은 {@code debug} 로 남긴다.
     * 모르는 사건이 자주 오면 로그가 그것으로 차므로 {@code warn} 으로 올리지 않는다.
     */
    public ExecutionEvent record(AgentExecution execution, RunEvent event, int sequence) {
        String hermesName = event.type() == null ? "" : event.type().toLowerCase(Locale.ROOT);
        ExecutionEventType type = OUR_NAMES.get(hermesName);
        if (type == null) {
            log.debug("옮겨 적을 이름이 없어 Hermes 사건을 버린다 event={}", hermesName);
            return null;
        }
        return ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(sequence)
                .eventType(type)
                .toolName(type.isTool() ? event.toolName() : null)
                .subagentName(type.isSubagent() ? event.toolName() : null)
                .hermesSessionId(type.isSubagent() ? event.childSessionId() : null)
                .durationMs(event.durationMs())
                .detail(type.isSubagent() && event.goal() != null ? event.goal() : event.detail())
                .model(type.isSubagent() ? event.model() : null)
                .inputTokens(type == ExecutionEventType.SUBAGENT_COMPLETED ? event.inputTokens() : null)
                .outputTokens(type == ExecutionEventType.SUBAGENT_COMPLETED ? event.outputTokens() : null)
                .occurredAt(Instant.now())
                .build();
    }

    /**
     * Hermes 사건을 기다리지 않고 우리가 직접 적는 사건이다.
     *
     * <p>실행의 시작과 끝은 스트림을 열지 않는 경로에서도 남아야 한다.
     */
    public ExecutionEvent record(
            AgentExecution execution, ExecutionEventType type, String detail, int sequence) {
        return ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(sequence)
                .eventType(type)
                .detail(detail)
                .occurredAt(Instant.now())
                .build();
    }
}
