package com.bifos.assistant.runtime.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.SteerOutcome;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 실행 port 의 구현이 모두 지켜야 하는 계약이다. 런타임 구현마다 이 테스트를 상속해 돌린다.
 *
 * <p>구현마다 사건을 만드는 방법이 달라, 하위 테스트가 정해진 실행을 준비하고 그 번호를 내준다.
 */
public abstract class AgentRuntimeContractTest {

    /** 도구 사건의 내용으로 흘려보내는 표식이다. 가린 사건에는 남으면 안 된다. */
    protected static final String TOOL_DETAIL_MARKER = "계약-도구-내용-4417";


    protected abstract AgentRuntime runtime();


    protected abstract RuntimeTarget target();


    /** 마지막 사건이 그 종류인 실행을 준비하고 번호를 준다. */
    protected abstract String runEndingWith(RunEventKind terminalKind);


    /** 내용이 {@link #TOOL_DETAIL_MARKER} 인 도구 시작과 끝 사건을 내는 실행을 준비하고 번호를 준다. */
    protected abstract String runWithToolDetail();


    /** 런타임이 모르는 실행 번호다. */
    protected abstract String unknownRunId();


    /** 도는 실행에 글을 끼울 수 있는 구현인가. */
    protected abstract boolean supportsSteer();


    @ParameterizedTest
    @DisplayName("끝 종류마다 끝 사건으로 알린다")
    @EnumSource(value = RunEventKind.class, names = {"RUN_COMPLETED", "RUN_FAILED", "RUN_CANCELLED", "RUN_INTERRUPTED"})
    void reportsEveryTerminalKind(RunEventKind terminalKind) {
        List<RunEvent> events = read(runEndingWith(terminalKind), ToolDetailScope.NONE);

        assertThat(events).isNotEmpty();
        RunEvent last = events.get(events.size() - 1);
        assertThat(last.kind()).isEqualTo(terminalKind);
        assertThat(last.kind().terminal()).isTrue();
    }


    @Test
    @DisplayName("모든 도구를 가리는 범위에서는 도구 내용을 원문으로 내지 않는다")
    void hidesToolDetailInScope() {
        List<RunEvent> events = read(runWithToolDetail(), ToolDetailScope.ALL);

        assertThat(events).extracting(RunEvent::kind).contains(RunEventKind.TOOL_STARTED, RunEventKind.TOOL_COMPLETED);
        assertThat(events).allSatisfy(event -> assertThat(event.toString()).doesNotContain(TOOL_DETAIL_MARKER));
    }


    @Test
    @DisplayName("모르는 실행을 물으면 NOT_FOUND 로 답한다")
    void answersNotFoundForUnknownRun() {
        assertThat(runtime().lookupRun(target(), unknownRunId()).state()).isEqualTo(RunLookup.State.NOT_FOUND);
    }


    @Test
    @DisplayName("글을 끼우지 못하는 구현은 REJECTED 로 답한다")
    void rejectsSteerWhenUnsupported() {
        assumeFalse(supportsSteer(), "글을 끼울 수 있는 구현은 이 사례를 건너뛴다");

        assertThat(runtime().steer(target(), unknownRunId(), "끼울 글")).isEqualTo(SteerOutcome.REJECTED);
    }


    private List<RunEvent> read(String runId, ToolDetailScope scope) {
        List<RunEvent> events = new ArrayList<>();
        runtime().openEvents(target(), runId, events::add, opened -> {
        }, scope);
        return events;
    }
}
