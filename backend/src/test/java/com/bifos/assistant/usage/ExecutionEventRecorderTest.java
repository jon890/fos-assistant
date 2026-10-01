package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hermes 사건을 우리 이름으로 옮기는 표를 검사한다.
 *
 * <p>여기 쓰는 Hermes 이름은 `docs/hermes/runs-api.md` 의 「실행 이벤트가 실제로 오는 형태」 절이
 * 실측해 적은 것이다. 짐작한 이름을 넣지 않는다.
 */
class ExecutionEventRecorderTest {

    /** 스킬 호출 이력은 따로 적는 자리가 맡는다. 여기서는 그 자리에 무엇을 넘기는지만 본다. */
    private final SkillUseRecorder skillUses = mock(SkillUseRecorder.class);

    private final ExecutionEventRecorder recorder = new ExecutionEventRecorder(skillUses);

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
    @DisplayName("도구를 부르기 시작한 사건을 TOOL STARTED로 옮기고 도구 이름을 채운다")
    void mapsToolStartEventToToolStartedAndFillsToolName() {
        ExecutionEvent event = record(hermes("tool.started", "web_search", "started"));

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.TOOL_STARTED);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.subagentName()).isNull();
        assertThat(event.detail()).isEqualTo("started");
        assertThat(event.sequence()).isEqualTo(1);
        assertThat(event.hermesSessionId()).isNull();
        assertThat(event.failed()).isNull();
    }

    /** 사건 스트림이 가리기 전 미리보기에서 꺼낸 스킬 이름을 함께 실은 사건이다. */
    private static RunEvent hermes(String name, String toolName, String detail, String skillName) {
        return new RunEvent(
                name, null, toolName, detail, null, null, null, null, null, null, null, null, null, skillName);
    }

    /**
     * 모델이 스킬을 읽은 것은 {@code skill_view} 도구의 시작 사건에만 실려 온다. 끝 사건과 다른 도구는 스킬
     * 사용이 아니다. 사건 자체는 다른 도구와 같이 옮겨 적는다.
     */
    @Test
    @DisplayName("skill view 도구의 시작 사건에서만 스킬 사용을 적는다")
    void recordsSkillUseOnlyFromSkillViewToolStartEvent() {
        ExecutionEvent started =
                record(hermes("tool.started", "skill_view", "shopping → references/list.md", "shopping"));
        record(hermes("tool.completed", "skill_view", "shopping", "shopping"));
        record(hermes("tool.started", "web_search", "shopping", "shopping"));
        record(hermes("subagent.start", "skill_view", "shopping", "shopping"));

        verify(skillUses).recordModel(EXECUTION.id(), "shopping");
        verifyNoMoreInteractions(skillUses);
        assertThat(started.eventType()).isEqualTo(ExecutionEventType.TOOL_STARTED);
        assertThat(started.toolName()).isEqualTo("skill_view");
    }

    /** 스킬 사용에는 가린 설명이 아니라 따로 실려 온 이름을 넘긴다. 저장하는 설명에는 그 이름을 싣지 않는다. */
    @Test
    @DisplayName("스킬 사용에는 가린 설명 대신 따로 실린 이름을 넘기고 저장하는 설명은 가린 값 그대로 둔다")
    void passesSeparateSkillNameInsteadOfRedactedDetailAndKeepsStoredDetailRedacted() {
        String longName = "weekly-grocery-shopping-list-builder-2026-v2";

        ExecutionEvent started = record(hermes("tool.started", "skill_view", "[가림]", longName));

        verify(skillUses).recordModel(EXECUTION.id(), longName);
        assertThat(started.detail()).isEqualTo("[가림]");
    }

    @Test
    @DisplayName("도구 호출이 끝난 사건에 걸린 시간을 채운다")
    void fillsElapsedTimeOnToolCallEndEvent() {
        ExecutionEvent event = record(new RunEvent("tool.completed", null, "web_search", "done", 1500L, false));

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.TOOL_COMPLETED);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.durationMs()).isEqualTo(1500L);
        assertThat(event.failed()).isFalse();
    }

    @Test
    @DisplayName("하위 에이전트 사건은 도구 사건과 어미가 다르다")
    void subagentEventSuffixDiffersFromToolEvent() {
        ExecutionEvent started = record(hermes("subagent.start", null, "탐색을 시작한다"));
        ExecutionEvent completed = record(hermes("subagent.complete", null, "탐색을 마쳤다"));

        assertThat(started.eventType()).isEqualTo(ExecutionEventType.SUBAGENT_STARTED);
        assertThat(completed.eventType()).isEqualTo(ExecutionEventType.SUBAGENT_COMPLETED);
        assertThat(started.toolName()).isNull();
        assertThat(started.subagentName()).isNull();
    }

    @Test
    @DisplayName("하위 에이전트 이름이 오면 subagentName에 채운다")
    void fillsSubagentNameWhenSubagentNameComes() {
        ExecutionEvent event = record(hermes("subagent.start", "researcher", null));

        assertThat(event.subagentName()).isEqualTo("researcher");
        assertThat(event.toolName()).isNull();
    }

    @Test
    @DisplayName("하위 에이전트의 목표와 session과 모델과 토큰만 옮긴다")
    void carriesOnlySubagentGoalSessionModelAndTokens() {
        RunEvent completed = new RunEvent(
                "subagent.complete",
                null,
                "researcher",
                "preview",
                1500L,
                false,
                "sa-1",
                "숙소 조사",
                "model-a",
                "child-1",
                123L,
                45L,
                "completed",
                null);
        ExecutionEvent event = record(completed);

        assertThat(event.detail()).isEqualTo("숙소 조사");
        assertThat(event.hermesSessionId()).isEqualTo("child-1");
        assertThat(event.model()).isEqualTo("model-a");
        assertThat(event.inputTokens()).isEqualTo(123L);
        assertThat(event.outputTokens()).isEqualTo(45L);
        assertThat(event.failed()).isFalse();
        assertThat(record(hermes("subagent.start", null, "preview")).detail()).isEqualTo("preview");
        ExecutionEvent tool = record(new RunEvent(
                "tool.completed",
                null,
                "search",
                "preview",
                1L,
                false,
                "sa-1",
                "숙소 조사",
                "model-a",
                "child-1",
                123L,
                45L,
                "completed",
                null));
        assertThat(tool.model()).isNull();
        assertThat(tool.inputTokens()).isNull();
        assertThat(tool.hermesSessionId()).isNull();
    }

    /**
     * 실행의 시작과 끝은 {@code ChatService} 가 직접 적는다. 여기서도 옮기면 스트리밍 경로에만 같은
     * 사건이 두 줄 남는다.
     */
    @Test
    @DisplayName("실행의 시작과 끝을 알리는 사건은 여기서 옮기지 않는다")
    void doesNotMapRunStartAndEndEventsHere() {
        assertThat(record(hermes("run.completed", null, null))).isNull();
        assertThat(record(hermes("run.failed", null, null))).isNull();
        assertThat(record(hermes("run.cancelled", null, null))).isNull();
    }

    @Test
    @DisplayName("모르는 사건은 예외를 던지지 않고 버린다")
    void dropsUnknownEventWithoutThrowing() {
        assertThatCode(() -> record(hermes("plugin.exploded", null, null))).doesNotThrowAnyException();

        assertThat(record(hermes("plugin.exploded", null, null))).isNull();
        assertThat(record(hermes(null, null, null))).isNull();
        assertThat(record(hermes("", null, null))).isNull();
    }

    @Test
    @DisplayName("글자 조각과 추론 사건은 저장하지 않는다")
    void doesNotStoreTextChunkAndReasoningEvents() {
        assertThat(record(new RunEvent("message.delta", "안녕", null, null, null, null)))
                .isNull();
        assertThat(record(new RunEvent("reasoning.available", "생각", null, null, null, null)))
                .isNull();
    }

    @Test
    @DisplayName("실패한 도구의 완료 사건은 실패로 저장한다")
    void storesCompletionEventOfFailedToolAsFailure() {
        ExecutionEvent event = record(new RunEvent("tool.completed", null, "search", "실패", 5L, true));

        assertThat(event.failed()).isTrue();
    }

    @Test
    @DisplayName("detail이 500자를 넘으면 자른다")
    void truncatesDetailOver500Chars() {
        String long501 = "가".repeat(ExecutionEvent.DETAIL_LIMIT + 1);

        ExecutionEvent event = record(hermes("tool.started", "web_search", long501));

        assertThat(event.detail()).hasSize(ExecutionEvent.DETAIL_LIMIT);
        assertThat(event.detail()).isEqualTo("가".repeat(ExecutionEvent.DETAIL_LIMIT));
    }

    @Test
    @DisplayName("detail이 정확히 500자면 그대로 둔다")
    void keepsDetailOfExactly500Chars() {
        String long500 = "가".repeat(ExecutionEvent.DETAIL_LIMIT);

        assertThat(record(hermes("tool.started", "web_search", long500)).detail())
                .isEqualTo(long500);
    }

    @Test
    @DisplayName("우리가 직접 적는 사건은 Hermes 이름 없이 만든다")
    void createsOwnEventsWithoutHermesName() {
        ExecutionEvent event = recorder.record(EXECUTION, ExecutionEventType.RUN_FAILED, "HERMES_UNAVAILABLE", 3);

        assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
        assertThat(event.detail()).isEqualTo("HERMES_UNAVAILABLE");
        assertThat(event.sequence()).isEqualTo(3);
        assertThat(event.toolName()).isNull();
        assertThat(event.durationMs()).isNull();
    }
}
