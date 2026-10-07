package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.chat.application.ModelVisibilityService;
import com.bifos.assistant.hermes.DecisionProfileReadinessClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ProfileModelDefaultsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.proactive.application.HermesDecisionProvider;
import com.bifos.assistant.proactive.application.ValueEvaluationProperties;
import com.bifos.assistant.proactive.application.ValueEvaluator;
import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class HermesDecisionProviderTest {

    private static final String PROFILE = "decision-test";
    private static final String BASE = "http://hermes.example.com";
    private final HermesRunsClient hermes = mock(HermesRunsClient.class);
    private final DecisionProfileReadinessClient readiness = mock(DecisionProfileReadinessClient.class);
    private final ProfileModelDefaultsClient defaults = mock(ProfileModelDefaultsClient.class);
    private final ModelVisibilityService visibility = mock(ModelVisibilityService.class);
    private final ExecutionRecorder executions = mock(ExecutionRecorder.class);
    private final UserExecutionLimiter limiter = mock(UserExecutionLimiter.class);
    private final AgentExecution execution = mock(AgentExecution.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final CurrentUser user = new CurrentUser(41L, "user@example.com", "사용자A", 1L, UserRole.MEMBER);

    @BeforeEach
    void setUp() {
        when(readiness.ready(PROFILE)).thenReturn(true);
        when(hermes.submit(any())).thenReturn("decision-run");
        when(executions.startSystem(any(), anyString(), any(), any())).thenReturn(execution);
        when(execution.id()).thenReturn(77L);
        when(execution.status()).thenReturn(ExecutionStatus.SUCCEEDED);
        when(execution.provider()).thenReturn("actual-provider");
        when(execution.model()).thenReturn("actual-model");
    }

    @Test
    @DisplayName("새 session 에 후보만 보내고 요청 모델과 실제 모델을 따로 남긴다")
    void sendsOnlySnapshotAndRecordsActualRuntime() {
        complete(json.writeValueAsString(DecisionFixtures.ordered(DecisionFixtures.state())));
        DecisionResponse response = evaluate(Duration.ofSeconds(1));
        ArgumentCaptor<HermesRunCommand> command = ArgumentCaptor.forClass(HermesRunCommand.class);
        verify(hermes).submit(command.capture());
        assertThat(command.getValue().profileName()).isEqualTo(PROFILE);
        assertThat(command.getValue().sessionId()).isNull();
        assertThat(command.getValue().input()).contains("<external-data>", "candidateId", "asOf");
        assertThat(command.getValue().input()).doesNotContain("user@example.com", "사용자A");
        assertThat(response.provider().requestedModel()).isEqualTo("requested-model");
        assertThat(response.provider().actualModel()).isEqualTo("actual-model");
        assertThat(response.provider().executionId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("도구 차단을 확인하지 못하면 Hermes 실행을 제출하지 않는다")
    void refusesProfileWhoseReadinessIsNotConfirmed() {
        when(readiness.ready(PROFILE)).thenReturn(false);
        assertThat(evaluate(Duration.ofSeconds(1)).result().failure()).isEqualTo(DecisionFailure.PROVIDER_UNAVAILABLE);
        verify(hermes, never()).submit(any());
        verify(executions, never()).startSystem(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("timeout 은 결과를 비우고 원격이 끝날 때까지 요청자의 실행 자리를 쥔다")
    void holdsRemoteExecutionAfterTimeout() {
        when(hermes.awaitCompletion(any(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        DecisionResponse response = evaluate(Duration.ofMillis(10));
        assertThat(response.result().failure()).isEqualTo(DecisionFailure.TIMEOUT);
        assertThat(response.result().orderedCandidateIds()).isEmpty();
        verify(limiter)
                .holdUntilRemoteEnds(user.id(), execution.id(), BASE + "/p/" + PROFILE, PROFILE, "decision-run", false);
        verify(executions).fail(execution, "DECISION_TIMEOUT");
    }

    @Test
    @DisplayName("JSON 뒤 다른 글과 잘못된 계약은 원시 응답을 저장하지 않고 거절한다")
    void rejectsTrailingTextAndMalformedContract() {
        String output = json.writeValueAsString(DecisionFixtures.ordered(DecisionFixtures.state()));
        complete(output + " {} ");
        assertThat(evaluate(Duration.ofSeconds(1)).result().failure()).isEqualTo(DecisionFailure.INVALID_RESULT);
        complete("private response that is not JSON");
        assertThat(evaluate(Duration.ofSeconds(1)).result().failure()).isEqualTo(DecisionFailure.INVALID_RESULT);
    }

    @Test
    @DisplayName("provider 오류 본문은 평가 기록으로 복제하지 않는다")
    void storesOnlyFailureCode() {
        when(hermes.awaitCompletion(any(), anyString()))
                .thenReturn(new HermesRunResult(
                        "decision-run", "session", "failed", null, null, null, "private error", null));
        DecisionResponse response = evaluate(Duration.ofSeconds(1));
        assertThat(response.result().failure()).isEqualTo(DecisionFailure.PROVIDER_FAILED);
        assertThat(json.writeValueAsString(response)).doesNotContain("private error");
        verify(executions).failSystem(eq(execution), any(), anyString(), eq("DECISION_PROVIDER_FAILED"));
    }

    private void complete(String output) {
        when(hermes.awaitCompletion(any(), anyString()))
                .thenReturn(HermesRunResult.of("decision-run", "fresh-session", "completed", output, null, null, null));
    }

    private DecisionResponse evaluate(Duration timeout) {
        ValueEvaluationProperties properties = new ValueEvaluationProperties(
                true, PROFILE, "requested-provider", "requested-model", "high", timeout, CostMode.SUBSCRIPTION);
        HermesProperties runtime = new HermesProperties(null, BASE, "synthetic", BASE, null, null, null, null);
        HermesDecisionProvider provider = new HermesDecisionProvider(
                properties, runtime, hermes, readiness, defaults, visibility, executions, limiter, json);
        return new ValueEvaluator()
                .evaluate(DecisionFixtures.state(), ValueEvaluator.QUESTIONS, provider, new DecisionRequest(user));
    }
}
