package com.bifos.assistant.usage.application;

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
        Long durationMs,
        String detail,
        Instant occurredAt) {

    static ExecutionEventView from(ExecutionEvent event) {
        return new ExecutionEventView(
                event.sequence(),
                event.eventType().name(),
                event.toolName(),
                event.subagentName(),
                event.durationMs(),
                event.detail(),
                event.occurredAt());
    }
}
