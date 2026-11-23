package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.application.dto.HermesRunEvent;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HermesRunEventKindTest {

    @ParameterizedTest
    @DisplayName("Hermes 사건 이름마다 맞는 종류로 옮기고 끝 사건 넷만 끝으로 본다")
    @CsvSource({
        "message.delta, MESSAGE_DELTA, false",
        "message.interim, MESSAGE_INTERIM, false",
        "tool.started, TOOL_STARTED, false",
        "tool.completed, TOOL_COMPLETED, false",
        "subagent.start, SUBAGENT_STARTED, false",
        "subagent.complete, SUBAGENT_COMPLETED, false",
        "run.completed, RUN_COMPLETED, true",
        "run.failed, RUN_FAILED, true",
        "run.cancelled, RUN_CANCELLED, true",
        "run.interrupted, RUN_INTERRUPTED, true"
    })
    void mapsHermesEventNames(String hermesType, RunEventKind kind, boolean terminal) {
        assertThat(HermesRunEventKinds.kindOf(hermesType)).isEqualTo(kind);
        assertThat(kind.terminal()).isEqualTo(terminal);
    }


    @Test
    @DisplayName("모르는 이름과 null 은 그 밖의 종류이고 끝 사건이 아니다")
    void mapsUnknownNamesToOther() {
        assertThat(HermesRunEventKinds.kindOf("tool.unknown")).isEqualTo(RunEventKind.OTHER);
        assertThat(HermesRunEventKinds.kindOf(null)).isEqualTo(RunEventKind.OTHER);
        assertThat(RunEventKind.OTHER.terminal()).isFalse();
    }


    @Test
    @DisplayName("대문자로 온 이름도 같은 종류로 옮긴다")
    void ignoresCase() {
        // Hermes 는 사건 이름을 소문자로 보낸다. 옮기기 전 ChatRunEvents 의 끝 사건 판정은 대소문자를 구분했고
        // 나머지 분기는 소문자로 바꿔 비교했다. 소문자만 오므로 한 번에 소문자로 옮겨도 결과가 같다.
        assertThat(HermesRunEventKinds.kindOf("RUN.COMPLETED")).isEqualTo(RunEventKind.RUN_COMPLETED);
        assertThat(HermesRunEventKinds.kindOf("Tool.Started")).isEqualTo(RunEventKind.TOOL_STARTED);
    }


    @Test
    @DisplayName("사건 이름만 종류로 바꾸고 나머지 칸은 그대로 옮긴다")
    void keepsOtherFields() {
        HermesRunEvent hermes = new HermesRunEvent(
            "subagent.complete", "글", "researcher", "설명", 1200L, Boolean.FALSE, "sub-1", "목표", "model-x",
            "child-1", 10L, 20L, "completed", "skill-a", Boolean.TRUE, 7, "interim-1");

        RunEvent event = HermesRunEventKinds.toRunEvent(hermes);

        assertThat(event).isEqualTo(new RunEvent(
            RunEventKind.SUBAGENT_COMPLETED, "글", "researcher", "설명", 1200L, Boolean.FALSE, "sub-1", "목표",
            "model-x", "child-1", 10L, 20L, "completed", "skill-a", Boolean.TRUE, 7, "interim-1"));
    }
}
