package com.bifos.assistant.usage.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import java.time.Instant;

/**
 * 실행 하나가 도는 동안 일어난 일 한 줄이다.
 *
 * <p>화면은 우리 이름만 읽는다. Hermes 의 원래 사건 이름은 여기까지 오지 않는다. 근거는 ADR-013 에
 * 있다.
 */
public record ExecutionEventView(
        int sequence,
        String eventType,
        String toolName,
        String subagentName,
        String hermesSessionId,
        Long durationMs,
        Boolean failed,
        String detail,
        String model,
        Long inputTokens,
        Long outputTokens,
        Instant occurredAt,
        String subagentUsageStatus) {

    /**
     * 사건 한 줄을 보는 사람에게 맞춰 옮긴다.
     *
     * <p>도구 사건의 {@code detail} 은 {@link ToolDetailPolicy} 가 허락할 때만 싣는다. 도구 사건이 아닌
     * 사건의 {@code detail} 은 그대로 싣는다.
     *
     * <p>{@code model} 과 토큰은 {@link InternalValuePolicy} 가 허락할 때만 싣는다.
     */
    static ExecutionEventView from(ExecutionEvent event, CurrentUser viewer) {
        return from(event, viewer, null);
    }

    static ExecutionEventView from(ExecutionEvent event, CurrentUser viewer, String subagentUsageStatus) {
        boolean hidden = event.eventType().isTool() && !ToolDetailPolicy.visibleTo(viewer, event.toolName());
        boolean internal = InternalValuePolicy.visibleTo(viewer);
        return new ExecutionEventView(
                event.sequence(),
                event.eventType().name(),
                event.toolName(),
                event.subagentName(),
                event.hermesSessionId(),
                event.durationMs(),
                event.failed(),
                hidden ? null : event.detail(),
                internal ? event.model() : null,
                internal ? event.inputTokens() : null,
                internal ? event.outputTokens() : null,
                event.occurredAt(),
                subagentUsageStatus);
    }
}
