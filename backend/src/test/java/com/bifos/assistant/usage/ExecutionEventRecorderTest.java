package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Hermes 사건을 우리 이름으로 옮기는 표를 검사한다.
 *
 * <p>여기 쓰는 Hermes 이름은 `docs/hermes-integration.md` 의 「실행 이벤트가 실제로 오는 형태」 절이
 * 실측해 적은 것이다. 짐작한 이름을 넣지 않는다.
 */
class ExecutionEventRecorderTest {

    private final ExecutionEventRecorder recorder = new ExecutionEventRecorder();

    private static final AgentExecution EXECUTION = AgentExecution.builder()
            .userId(1L)
            .conversationId(2L)
            .profileName("dad")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.parse("2026-09-18T00:00:00Z"))
            .build();

    private ExecutionEvent record(RunEvent event) {
        return recorder.record(EXECUTION, event, 1);
    }

    private static RunEvent hermes(String name, String toolName, String detail) {
        return new RunEvent(name, null, toolName, detail, null, null);
    }

    @Test
    void 도구를_부르기_시작한_사건을_TOOL_STARTED로_옮기고_도구_이름을_채운다() {
        ExecutionEvent event = record(hermes("tool.started", "web_search", "started"));

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.TOOL_STARTED);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.subagentName()).isNull();
        assertThat(event.detail()).isEqualTo("started");
        assertThat(event.sequence()).isEqualTo(1);
        assertThat(event.hermesSessionId()).isNull();
    }

    @Test
    void 도구_호출이_끝난_사건에_걸린_시간을_채운다() {
        ExecutionEvent event =
                record(new RunEvent("tool.completed", null, "web_search", "done", 1500L, false));

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.TOOL_COMPLETED);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.durationMs()).isEqualTo(1500L);
    }

    @Test
    void 하위_에이전트_사건은_도구_사건과_어미가_다르다() {
        ExecutionEvent started = record(hermes("subagent.start", null, "탐색을 시작한다"));
        ExecutionEvent completed = record(hermes("subagent.complete", null, "탐색을 마쳤다"));

        assertThat(started.eventType()).isEqualTo(ExecutionEventType.SUBAGENT_STARTED);
        assertThat(completed.eventType()).isEqualTo(ExecutionEventType.SUBAGENT_COMPLETED);
        assertThat(started.toolName()).isNull();
        assertThat(started.subagentName()).isNull();
    }

    @Test
    void 하위_에이전트_이름이_오면_subagentName에_채운다() {
        ExecutionEvent event = record(hermes("subagent.start", "researcher", null));

        assertThat(event.subagentName()).isEqualTo("researcher");
        assertThat(event.toolName()).isNull();
    }

    /**
     * 실행의 시작과 끝은 {@code ChatService} 가 직접 적는다. 여기서도 옮기면 스트리밍 경로에만 같은
     * 사건이 두 줄 남는다.
     */
    @Test
    void 실행의_시작과_끝을_알리는_사건은_여기서_옮기지_않는다() {
        assertThat(record(hermes("run.completed", null, null))).isNull();
        assertThat(record(hermes("run.failed", null, null))).isNull();
        assertThat(record(hermes("run.cancelled", null, null))).isNull();
    }

    @Test
    void 모르는_사건은_예외를_던지지_않고_버린다() {
        assertThatCode(() -> record(hermes("plugin.exploded", null, null))).doesNotThrowAnyException();

        assertThat(record(hermes("plugin.exploded", null, null))).isNull();
        assertThat(record(hermes(null, null, null))).isNull();
        assertThat(record(hermes("", null, null))).isNull();
    }

    @Test
    void 글자_조각과_추론_사건은_저장하지_않는다() {
        assertThat(record(new RunEvent("message.delta", "안녕", null, null, null, null))).isNull();
        assertThat(record(new RunEvent("reasoning.available", "생각", null, null, null, null))).isNull();
    }

    @Test
    void detail이_500자를_넘으면_자른다() {
        String long501 = "가".repeat(ExecutionEvent.DETAIL_LIMIT + 1);

        ExecutionEvent event = record(hermes("tool.started", "web_search", long501));

        assertThat(event.detail()).hasSize(ExecutionEvent.DETAIL_LIMIT);
        assertThat(event.detail()).isEqualTo("가".repeat(ExecutionEvent.DETAIL_LIMIT));
    }

    @Test
    void detail이_정확히_500자면_그대로_둔다() {
        String long500 = "가".repeat(ExecutionEvent.DETAIL_LIMIT);

        assertThat(record(hermes("tool.started", "web_search", long500)).detail()).isEqualTo(long500);
    }

    @Test
    void 우리가_직접_적는_사건은_Hermes_이름_없이_만든다() {
        ExecutionEvent event =
                recorder.record(EXECUTION, ExecutionEventType.RUN_FAILED, "HERMES_UNAVAILABLE", 3);

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
        assertThat(event.detail()).isEqualTo("HERMES_UNAVAILABLE");
        assertThat(event.sequence()).isEqualTo(3);
        assertThat(event.toolName()).isNull();
        assertThat(event.durationMs()).isNull();
    }
}
