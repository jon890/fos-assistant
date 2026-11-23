package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunLookup;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.runtime.application.dto.RunCommand;
import com.bifos.assistant.runtime.application.dto.RunImage;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.RunOutcome;
import com.bifos.assistant.runtime.application.dto.RunResult;
import com.bifos.assistant.runtime.application.dto.SessionRuntime;
import com.bifos.assistant.runtime.application.dto.SteerOutcome;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HermesAgentRuntimeTest {

    private static final String LOCATION = "http://runtime.test/p/dad";
    private static final String UNIT = "dad";
    private static final RuntimeTarget TARGET = new RuntimeTarget(RuntimeKind.HERMES, LOCATION, UNIT);

    private final HermesRunsClient client = mock(HermesRunsClient.class);
    private final HermesRunEventStream stream = mock(HermesRunEventStream.class);
    private final HermesAgentRuntime runtime = new HermesAgentRuntime(client, stream);


    @Test
    @DisplayName("명령의 대상을 Hermes 의 주소와 profile 로 옮겨 제출하고 결과를 중립 결과로 돌려준다")
    void submitsWithProfileAndBaseUrl() {
        List<RunImage> images = List.of(new RunImage("image/png", "aGVsbG8="));
        RunCommand command = new RunCommand(TARGET, "입력", "지침", "session-1", "openai-codex", "model-x", "high", images);
        ArgumentCaptor<HermesRunCommand> sent = ArgumentCaptor.forClass(HermesRunCommand.class);
        when(client.submit(sent.capture())).thenReturn("run-1");
        when(client.awaitCompletion(any(), eq("run-1"))).thenReturn(
            new HermesRunResult("run-1", "session-1", "completed", "답", "model-x", "openai-codex", null,
                TokenUsage.empty()));

        String runId = runtime.submit(command);
        RunResult result = runtime.awaitCompletion(command, runId);

        assertThat(sent.getValue()).isEqualTo(new HermesRunCommand(
            UNIT, LOCATION, "입력", "지침", "session-1", "openai-codex", "model-x", "high", images));
        assertThat(result.outcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(result.statusLabel()).isEqualTo("completed");
        assertThat(result.output()).isEqualTo("답");
    }


    @Test
    @DisplayName("중지, 글 끼우기, 조회, 세션 삭제, 잃은 실행 정리에 대상의 주소와 profile 을 넘긴다")
    void passesTargetToRunCalls() {
        when(client.steer(LOCATION, UNIT, "run-1", "끼울 글")).thenReturn(SteerOutcome.ACCEPTED);
        when(client.lookupRun(LOCATION, UNIT, "run-1")).thenReturn(HermesRunLookup.running());

        runtime.stop(TARGET, "run-1");
        runtime.deleteSession(TARGET, "session-1");
        runtime.releaseIfLost(TARGET, "run-1");

        assertThat(runtime.steer(TARGET, "run-1", "끼울 글")).isEqualTo(SteerOutcome.ACCEPTED);
        assertThat(runtime.lookupRun(TARGET, "run-1").state()).isEqualTo(RunLookup.State.RUNNING);
        verify(client).stop(LOCATION, UNIT, "run-1");
        verify(client).deleteSession(LOCATION, UNIT, "session-1");
        verify(client).releaseIfLost(LOCATION, UNIT, "run-1");
    }


    @Test
    @DisplayName("실제로 돈 모델과 하위 에이전트 사용량을 대상의 주소와 profile 로 읽는다")
    void readsSessionRuntimeAndSubagentUsage() {
        SessionRuntime sessionRuntime = new SessionRuntime("model-real", "openai-codex");
        when(client.readSessionRuntime(LOCATION, UNIT, "session-1")).thenReturn(sessionRuntime);

        assertThat(runtime.readSessionRuntime(TARGET, "session-1")).isSameAs(sessionRuntime);
        assertThat(runtime.readSubagentUsage(TARGET, "child-1")).isNull();
        verify(client).readSubagentUsage(LOCATION, UNIT, "child-1");
    }


    @Test
    @DisplayName("사건 스트림을 대상의 주소와 profile, 받은 범위로 연다")
    void opensEventStreamWithTargetAndScope() {
        runtime.openEvents(TARGET, "run-1", event -> {
        }, opened -> {
        }, ToolDetailScope.ALL);

        verify(stream).open(eq(LOCATION), eq(UNIT), eq("run-1"), any(), any(), eq(ToolDetailScope.ALL));
    }
}
