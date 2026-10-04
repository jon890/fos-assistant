package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ResolvedModelTier;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.RunSession;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.orchestration.application.AgentRun;
import com.bifos.assistant.orchestration.application.AgentRunner;
import com.bifos.assistant.orchestration.application.DelegationOutput;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hermes 에 제출한 뒤 실행 줄을 갱신하다 실패하면 그 run 을 멈추고 줄을 FAILED 로 적는 것을 고정한다.
 *
 * <p>실행 줄만 FAILED 로 남기고 run 을 두면 Hermes 에서는 끝까지 돌아 토큰을 쓰고, 그 결과를 받을 곳도 없다.
 */
class AgentRunnerSubmitFailureTest {

    private static final String API_BASE_URL = "http://agent-runtime.test/p/runner";
    private static final String PROFILE = "runner-profile";
    private static final String RUN_ID = "run-submitted";

    private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
    private final HermesRunsClient hermes = mock(HermesRunsClient.class);
    private final ExecutionRecorder executions = mock(ExecutionRecorder.class);
    private final ModelTierService modelTiers = mock(ModelTierService.class);
    private final UserExecutionLimiter limiter = mock(UserExecutionLimiter.class);
    private final CurrentUser user = new CurrentUser(1L, "runner@example.com", "가", 1L, UserRole.MEMBER);
    private final Agent agent = Agent.of(
            "runner",
            "실행기",
            PROFILE,
            API_BASE_URL,
            CostMode.API,
            CredentialScope.DEDICATED,
            AgentVisibility.PRIVATE,
            1L,
            Instant.now());
    private final AgentExecution started = mock(AgentExecution.class);
    private final AgentExecution failed = mock(AgentExecution.class);
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        when(contextAssembler.assemble(eq(user), any())).thenReturn(new AssembledContext(null, 0));
        when(contextAssembler.withResponseInstructions(any())).thenCallRealMethod();
        when(started.id()).thenReturn(3L);
        when(failed.id()).thenReturn(3L);
        when(failed.status()).thenReturn(ExecutionStatus.FAILED);
        when(modelTiers.resolve(any(), any(), any())).thenReturn(new ResolvedModelTier(ModelChoice.defaults(), null));
        when(executions.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(started);
        when(executions.fail(any(), anyString())).thenReturn(failed);
        runner = new AgentRunner(
                contextAssembler,
                hermes,
                executions,
                mock(ExecutionEventRecorder.class),
                mock(ExecutionEventRepository.class),
                new DelegationOutput(
                        new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 100, Duration.ofSeconds(20))),
                modelTiers,
                Clock.systemUTC(),
                limiter);
    }

    @Test
    @DisplayName("제출 뒤 run 번호를 적지 못하면 그 run 을 멈추고 FAILED 로 끝낸다")
    void stopsRunAndEndsFailedWhenRunIdCannotBeRecordedAfterSubmit() {
        when(hermes.submit(any())).thenReturn(RUN_ID);
        doThrow(new IllegalStateException("저장 실패")).when(executions).attachRunId(started, RUN_ID);

        AgentRun run = run();

        verify(hermes).stop(API_BASE_URL, PROFILE, RUN_ID);
        verify(limiter).holdUntilRemoteEnds(user.id(), started.id(), API_BASE_URL, PROFILE, RUN_ID, true);
        verify(executions).fail(started, "ORCHESTRATION_STEP_FAILED");
        assertThat(run.execution()).isSameAs(failed);
        assertThat(run.result().succeeded()).isFalse();
        assertThat(run.result().errorCode()).isEqualTo("ORCHESTRATION_STEP_FAILED");
        verify(hermes, never()).awaitCompletion(any(), any());
    }

    @Test
    @DisplayName("run 을 멈추지 못해도 실행은 FAILED 로 끝난다")
    void endsFailedEvenIfRunCannotBeStopped() {
        when(hermes.submit(any())).thenReturn(RUN_ID);
        doThrow(new IllegalStateException("저장 실패")).when(executions).attachRunId(started, RUN_ID);
        doThrow(new IllegalStateException("Hermes 에 닿지 못했다")).when(hermes).stop(API_BASE_URL, PROFILE, RUN_ID);

        AgentRun run = run();

        verify(executions).fail(started, "ORCHESTRATION_STEP_FAILED");
        assertThat(run.result().succeeded()).isFalse();
    }

    @Test
    @DisplayName("제출 자체가 실패하면 멈출 run 이 없다")
    void hasNoRunToStopWhenSubmitItselfFails() {
        when(hermes.submit(any())).thenThrow(new IllegalStateException("제출 실패"));

        AgentRun run = run();

        verify(hermes, never()).stop(any(), any(), any());
        verify(limiter, never()).holdUntilRemoteEnds(any(), any(), any(), any(), any(), anyBoolean());
        verify(executions).fail(started, "ORCHESTRATION_STEP_FAILED");
        assertThat(run.result().succeeded()).isFalse();
    }

    @Test
    @DisplayName("기다리다 시간 초과로 끝나면 중지를 아직 보내지 않은 run 으로 사용자 자리를 쥐고 FAILED 로 끝낸다")
    void holdsUserSlotWithoutStopWhenAwaitTimesOut() {
        when(hermes.submit(any())).thenReturn(RUN_ID);
        when(hermes.awaitCompletion(any(), eq(RUN_ID)))
                .thenThrow(new ApiException(ErrorCode.HERMES_RUN_TIMEOUT, "the agent run did not finish in time"));

        AgentRun run = run();

        verify(limiter).holdUntilRemoteEnds(user.id(), started.id(), API_BASE_URL, PROFILE, RUN_ID, false);
        verify(executions).fail(started, ErrorCode.HERMES_RUN_TIMEOUT.name());
        assertThat(run.result().errorCode()).isEqualTo(ErrorCode.HERMES_RUN_TIMEOUT.name());
    }

    @Test
    @DisplayName("단계 모델이 catalog에 없으면 chief 실행을 FAILED로 남기고 Hermes에 제출하지 않는다")
    void recordsFailedChiefWhenModelTierCannotBeResolved() {
        when(modelTiers.resolve(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable"));

        AgentRun run = run();

        verify(executions).fail(started, ErrorCode.VALIDATION_FAILED.name());
        verify(hermes, never()).submit(any());
        assertThat(run.execution()).isSameAs(failed);
        assertThat(run.result().errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    }

    private AgentRun run() {
        return runner.run(
                user,
                Conversation.startedBy(user.id(), "대화", 2L, Instant.now()),
                agent,
                "일",
                null,
                null,
                RunSession.fresh(),
                execution -> {},
                (execution, runId) -> {},
                () -> false,
                null);
    }
}
