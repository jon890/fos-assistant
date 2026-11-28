package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.orchestration.application.dto.PluginCallEnded;
import com.bifos.assistant.orchestration.application.dto.ToolTraceRecord;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import com.bifos.assistant.runtime.domain.ToolDetailRedactor;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import com.bifos.assistant.usage.execution.application.ToolTraceWriter;
import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import com.bifos.assistant.usage.execution.application.port.ExecutionConnectorCalls;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.infra.RunningExecutionRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * plugin 이 보낸 도구 기록을 그 실행의 도구 완료 줄에 옮긴다(docs/features/run-trace/design.md).
 *
 * <p>실행은 {@link EventToolGuard} 와 같은 순서로 푼다. 등록된 하위 에이전트 session 이면 그 origin 실행이고, 아니면 서명한 루트
 * session 으로 도구가 시작한 시각에 돌던 그 profile 의 실행이 하나일 때 그 실행이다. 도는 실행만 찾지 않는 것은 실행이 끝난 직후
 * 도착한 기록이 같은 대화의 다음 turn 에 붙지 않게 하려는 것이다. 둘 이상이거나 없으면 버린다. 남의 실행에 붙이지 않는 것이 버리는
 * 것보다 중요하다. 이번 단계(PR1)는 등록된 하위 에이전트 session 의 기록과 호출 번호가 없는 기록(코드 실행 안의 호출)을 받기만 하고 버린다.
 * 둘 다 짝지을 사건 줄이 없어서다.
 *
 * <p>오류 글은 받을 때 그 실행의 가림 범위로 가린다. 범위는 맡긴 실행의 사건 스트림({@code ChildRunEvents.detailScope})과 같다.
 * 원문은 로그에도 남기지 않는다(스펙 run-trace REQ6).
 *
 * <p>호출 번호가 있는 기록은 실행을 찾기 전에 {@link PluginCallEnded} 사건으로도 낸다. 하위 에이전트 session 의 기록도 낸다. 화면 넘기기가
 * 그 플러그인의 다음 호출이 끝났는지 본다(docs/features/mobile-default-chat/design.md 「플러그인 성공으로 닫기」).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RunTraceIntake {

    private final HermesSessionBindingRepository bindings;
    private final RunningExecutionRepository executions;
    private final AgentConnectorBindings connectorBindings;
    private final ExecutionConnectorCalls connectorCalls;
    private final ToolTraceWriter toolTraces;
    private final ApplicationEventPublisher events;

    /**
     * Hermes 와 Control Plane 의 시계 차이를 감안하는 폭이다. turn 경계에 걸린 기록은 후보가 둘이 되어 버려진다.
     */
    static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(1);


    /**
     * @param profileName 토큰이 증명한 profile
     * @return 실행에 옮긴 기록의 수
     */
    public int receive(String profileName, List<ToolTraceRecord> records) {
        int accepted = 0;
        int dropped = 0;
        for (ToolTraceRecord record : records) {
            try {
                if (receive(profileName, record)) {
                    accepted++;
                } else {
                    dropped++;
                }
            } catch (RuntimeException ex) {
                dropped++;
                log.warn("도구 기록 하나를 옮기지 못했다 profile={} reason={}", profileName, ex.getClass().getSimpleName());
            }
        }
        if (dropped > 0) {
            log.info("실행에 붙이지 못한 도구 기록을 버렸다 profile={} dropped={}", profileName, dropped);
        }
        return accepted;
    }


    private boolean receive(String profileName, ToolTraceRecord record) {
        if (record.toolCallId() == null || record.toolCallId().isBlank()) {
            return false;
        }
        announce(profileName, record);
        if (bindings.findByProfileNameAndSessionId(profileName, record.sessionId()).isPresent()) {
            return false;
        }
        Optional<AgentExecution> execution = runningAt(profileName, record);
        if (execution.isEmpty()) {
            return false;
        }
        toolTraces.receive(execution.get().id(), record.toolCallId(), trace(execution.get(), record));
        return true;
    }


    /** 듣는 쪽의 실패는 기록 옮기기를 막지 않는다. 예외 본문에 원격 글이 섞일 수 있어 종류만 남긴다. */
    private void announce(String profileName, ToolTraceRecord record) {
        try {
            events.publishEvent(new PluginCallEnded(
                profileName, record.rootSessionId(), record.sessionId(), record.toolCallId(), record.failed()));
        } catch (RuntimeException ex) {
            log.warn("도구 호출의 끝을 알리지 못했다 profile={} reason={}", profileName, ex.getClass().getSimpleName());
        }
    }


    private Optional<AgentExecution> runningAt(String profileName, ToolTraceRecord record) {
        if (record.startedAt() == null) {
            return Optional.empty();
        }
        List<AgentExecution> candidates = executions.findRunningAt(
            profileName,
            record.rootSessionId(),
            record.startedAt().plus(CLOCK_TOLERANCE),
            record.startedAt().minus(CLOCK_TOLERANCE),
            PageRequest.of(0, 2));
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }


    private ToolTrace trace(AgentExecution execution, ToolTraceRecord record) {
        if (!record.failed()) {
            return new ToolTrace(record.toolName(), record.durationMs(), null, null);
        }
        ToolDetailScope scope = ToolDetailScope.prefixes(prefixes(execution));
        String summary = ToolDetailRedactor.redactError(
            record.errorMessage(), record.toolName(), scope, afterConnector(execution, scope, record));
        return new ToolTrace(record.toolName(), record.durationMs(), errorType(record.errorType()), summary);
    }


    private Set<String> prefixes(AgentExecution execution) {
        return execution.agentId() == null ? Set.of() : connectorBindings.connectorToolPrefixes(execution.agentId());
    }


    /** 그 도구가 시작한 시각까지 트리에서 플러그인을 불렀는가. 시각을 모르면 불렀다고 본다. 조회가 실패해도 참이다. */
    private boolean afterConnector(AgentExecution execution, ToolDetailScope scope, ToolTraceRecord record) {
        if (scope.hides(record.toolName())) {
            return true;
        }
        if (record.startedAt() == null) {
            return true;
        }
        return connectorCalls.calledBefore(execution.treeRootId(), record.startedAt());
    }


    private static String errorType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= ExecutionEvent.ERROR_TYPE_LIMIT
            ? stripped
            : stripped.substring(0, ExecutionEvent.ERROR_TYPE_LIMIT);
    }
}
