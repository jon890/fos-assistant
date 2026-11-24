package com.bifos.assistant.hermes.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.bifos.assistant.hermes.application.dto.HermesRunEvent;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import java.util.Locale;
import java.util.Map;

/** Hermes 가 {@code event} 로 보내는 사건 이름을 런타임에 묶이지 않는 종류로 옮긴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HermesRunEventKinds {

    private static final Map<String, RunEventKind> KINDS = Map.of(
        "message.delta", RunEventKind.MESSAGE_DELTA,
        "message.interim", RunEventKind.MESSAGE_INTERIM,
        "tool.started", RunEventKind.TOOL_STARTED,
        "tool.completed", RunEventKind.TOOL_COMPLETED,
        "subagent.start", RunEventKind.SUBAGENT_STARTED,
        "subagent.complete", RunEventKind.SUBAGENT_COMPLETED,
        "run.completed", RunEventKind.RUN_COMPLETED,
        "run.failed", RunEventKind.RUN_FAILED,
        "run.cancelled", RunEventKind.RUN_CANCELLED,
        "run.interrupted", RunEventKind.RUN_INTERRUPTED);


    /** 모르는 이름과 null 은 {@code OTHER} 다. 대소문자는 가리지 않는다. */
    public static RunEventKind kindOf(String hermesType) {
        if (hermesType == null) {
            return RunEventKind.OTHER;
        }
        return KINDS.getOrDefault(hermesType.toLowerCase(Locale.ROOT), RunEventKind.OTHER);
    }


    /** 사건 이름만 종류로 옮기고 나머지 칸은 그대로 둔다. */
    public static RunEvent toRunEvent(HermesRunEvent event) {
        return new RunEvent(
            kindOf(event.type()),
            event.text(),
            event.toolName(),
            event.detail(),
            event.durationMs(),
            event.failed(),
            event.subagentId(),
            event.goal(),
            event.model(),
            event.childSessionId(),
            event.inputTokens(),
            event.outputTokens(),
            event.status(),
            event.skillName(),
            event.alreadyStreamed(),
            event.streamedPrefixLength(),
            event.interimId(),
            event.toolCallId());
    }
}
