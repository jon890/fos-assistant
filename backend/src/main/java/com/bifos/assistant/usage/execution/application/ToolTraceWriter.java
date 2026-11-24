package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.usage.execution.application.dto.ToolTrace;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * plugin 의 도구 기록을 그 도구의 {@code TOOL_COMPLETED} 줄에 옮긴다(스펙 run-trace REQ2).
 *
 * <p>새 사건 줄은 넣지 않는다. 실행마다 메모리에 있는 사건 번호와 겹치기 때문이다(설계 DECISION2). 도착 순서는
 * {@link ToolTraceLedger} 가 맞춘다. 무엇이 실패해도 로그만 남기고 사건 저장과 대화를 건드리지 않는다.
 *
 * <p>오류 종류와 요약은 사건의 실패 표시가 참인 줄에만 옮긴다. hook 과 사건은 실패를 다르게 판정할 수 있어, 실패 표시가 없는 줄에
 * 오류가 붙어 보이지 않게 한다. 걸린 시간은 줄에 값이 없을 때만 채운다. 사건 줄과 도구 이름이 다르면 옮기지 않는다. 다만 Tool
 * Search 의 {@code tool_call} 은 사건이 다리 이름을, hook 이 실제 도구 이름을 보내므로 이름을 보지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ToolTraceWriter {

    /** Tool Search 가 실제 도구를 대신 부르는 다리 도구다. 사건에는 이 이름이, hook 에는 실제 이름이 온다. */
    static final String BRIDGE_TOOL = "tool_call";

    private final ToolTraceLedger ledger;
    private final ExecutionEventRepository events;


    /** 완료 줄을 저장하기 전에 부른다. 먼저 와서 기다리던 기록을 줄에 옮긴다. */
    public void fill(ExecutionEvent completed) {
        if (!traceable(completed)) {
            return;
        }
        ToolTrace trace = ledger.claim(completed.executionId(), completed.toolCallId());
        if (trace != null) {
            apply(completed, trace);
        }
    }


    /** 사건을 저장한 직후 부른다. 만들기와 저장 사이에 온 기록이 있으면 그 줄을 고친다. */
    public void afterSaved(ExecutionEvent saved) {
        if (!traceable(saved) || saved.id() == null) {
            return;
        }
        try {
            ToolTrace late = ledger.saved(saved.executionId(), saved.toolCallId(), saved.id());
            if (late != null) {
                update(saved.id(), late);
            }
        } catch (RuntimeException ex) {
            log.warn("도구 기록을 완료 줄에 옮기지 못했다 executionId={}", saved.executionId(), ex);
        }
    }


    /** plugin 기록이 왔을 때 부른다. 완료 줄이 이미 있으면 고치고, 없으면 장부에 맡긴다. */
    public void receive(Long executionId, String toolCallId, ToolTrace trace) {
        Long eventId = ledger.offer(executionId, toolCallId, trace);
        if (eventId != null) {
            update(eventId, trace);
        }
    }


    /** 저장된 줄은 바꾸는 칸만 쓴다. 같은 규칙을 질의가 갖는다({@link ExecutionEventRepository#recordToolTrace}). */
    private void update(Long eventId, ToolTrace trace) {
        events.recordToolTrace(eventId, trace.toolName(), trace.durationMs(), trace.errorType(), trace.errorSummary());
    }


    private static boolean apply(ExecutionEvent event, ToolTrace trace) {
        if (trace.toolName() != null
            && event.toolName() != null
            && !event.toolName().equals(trace.toolName())
            && !BRIDGE_TOOL.equals(event.toolName())) {
            return false;
        }
        boolean failed = Boolean.TRUE.equals(event.failed());
        event.recordToolTrace(
            trace.durationMs(), failed ? trace.errorType() : null, failed ? trace.errorSummary() : null);
        return true;
    }


    private static boolean traceable(ExecutionEvent event) {
        return event != null
            && event.eventType() == ExecutionEventType.TOOL_COMPLETED
            && event.toolCallId() != null;
    }
}
