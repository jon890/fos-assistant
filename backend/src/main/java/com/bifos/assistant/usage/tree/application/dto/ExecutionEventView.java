package com.bifos.assistant.usage.tree.application.dto;

import com.bifos.assistant.usage.execution.domain.InternalValuePolicy;
import com.bifos.assistant.usage.execution.domain.ToolDetailPolicy;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import java.time.Instant;

/**
 * 실행 하나가 도는 동안 일어난 일 한 줄이다.
 *
 * <p>화면은 우리 이름만 읽는다. Hermes 의 원래 사건 이름은 여기까지 오지 않는다. 근거는 ADR-013 에
 * 있다.
 *
 * <p>{@code connector} 는 플러그인 도구 사건에만 싣는 서비스 이름과 도구 제목이다. 플러그인 선언에 있는 공개 정보라 누구에게나
 * 싣는다(docs/features/app-shell/design.md 「작업 과정의 이름」).
 *
 * <p>{@code delegatedAgentName} 은 {@code agent_delegate} 도구 사건에만 넣는 맡은 에이전트의 이름이다. 모델이 준 인자가 아니라 그 호출이
 * 도는 동안 시작된 맡긴 실행의 에이전트에서 정한다. 찾지 못하면 null 이고 화면은 「다른 에이전트」 로 보인다.
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
    String subagentUsageStatus,
    ConnectorToolName connector,
    String delegatedAgentName,
    String errorType,
    String errorSummary,
    String toolCallId) {

    /**
     * 사건 한 줄을 보는 사람에게 맞춰 옮긴다.
     *
     * <p>도구 사건의 {@code detail} 은 {@link ToolDetailPolicy} 가 허락할 때만 싣는다. 도구 사건이 아닌
     * 사건의 {@code detail} 은 그대로 싣는다.
     *
     * <p>{@code model} 과 토큰, 실패한 도구의 오류 종류와 요약은 {@link InternalValuePolicy} 가 허락할 때만 싣는다(스펙
     * run-trace REQ3). {@code toolCallId} 는 Hermes 가 붙인 불투명한 번호라 누구에게나 싣는다. 화면이 같은 이름의 병렬 도구를 바른
     * 짝으로 묶는 데만 쓴다(docs/features/run-trace/design.md DECISION8).
     */
    public static ExecutionEventView from(ExecutionEvent event, CurrentUser viewer) {
        return from(event, viewer, null);
    }


    public static ExecutionEventView from(ExecutionEvent event, CurrentUser viewer, String subagentUsageStatus) {
        return from(event, viewer, subagentUsageStatus, null);
    }


    public static ExecutionEventView from(
        ExecutionEvent event, CurrentUser viewer, String subagentUsageStatus, ConnectorToolName connector) {
        return from(event, viewer, subagentUsageStatus, connector, null);
    }


    public static ExecutionEventView from(
        ExecutionEvent event, CurrentUser viewer, String subagentUsageStatus, ConnectorToolName connector,
        String delegatedAgentName) {
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
            subagentUsageStatus,
            connector,
            delegatedAgentName,
            internal ? event.errorType() : null,
            internal ? event.errorSummary() : null,
            event.toolCallId());
    }
}
