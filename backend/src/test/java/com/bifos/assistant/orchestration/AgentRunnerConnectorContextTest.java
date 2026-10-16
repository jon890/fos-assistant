package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
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
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.orchestration.application.AgentRun;
import com.bifos.assistant.orchestration.application.AgentRunner;
import com.bifos.assistant.orchestration.application.DelegationOutput;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 위임 실행의 문맥과 모델, 실행 시각·답 기록 계약을 확인한다.
 */
class AgentRunnerConnectorContextTest {

    private static final String RUN_ID = "run-connector";
    private static final String MEMORY = "## 기억\n국수는 맵지 않게 먹는다";
    private static final String ADDITION = "이번 실행에만 주는 지시";
    private static final String ANSWER = "일정 두 건을 찾았다";

    private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
    private final HermesRunsClient hermes = mock(HermesRunsClient.class);
    private final ExecutionRecorder executions = mock(ExecutionRecorder.class);
    private final ModelTierService modelTiers = mock(ModelTierService.class);
    private final ProactiveCheckRepository proactiveChecks = mock(ProactiveCheckRepository.class);
    private static final Instant REQUEST_RECEIVED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private final CurrentUser user = new CurrentUser(1L, "runner@example.com", "가", 1L, UserRole.MEMBER);
    private final AgentExecution started = mock(AgentExecution.class);
    private final AgentExecution completed = mock(AgentExecution.class);
    private AgentRunner runner;


    @BeforeEach
    void setUp() {
        when(contextAssembler.assemble(eq(user), any(), any())).thenReturn(new AssembledContext(MEMORY, MEMORY.length()));
        when(contextAssembler.withResponseInstructions(any(), anyBoolean())).thenCallRealMethod();
        when(started.id()).thenReturn(3L);
        when(completed.id()).thenReturn(3L);
        when(modelTiers.resolve(any(), any(), any())).thenReturn(new ResolvedModelTier(ModelChoice.defaults(), null));
        when(executions.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(started);
        when(executions.complete(any(), any(), any(), any())).thenReturn(completed);
        when(executions.complete(any(), any(), any(), any(), any())).thenReturn(completed);
        when(hermes.submit(any())).thenReturn(RUN_ID);
        when(hermes.awaitCompletion(any(), eq(RUN_ID)))
            .thenReturn(HermesRunResult.of(
                RUN_ID, "sess-1", "completed", ANSWER, "example-model", null, TokenUsage.empty()));
        runner = new AgentRunner(
            contextAssembler,
            hermes,
            executions,
            mock(ExecutionEventRecorder.class),
            mock(ExecutionEventRepository.class),
            new DelegationOutput(LiveProperties.fixed(
                DelegationProperties.class,
                new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 100, Duration.ofSeconds(20)))),
            modelTiers,
            Clock.fixed(REQUEST_RECEIVED_AT, ZoneOffset.UTC),
            mock(UserExecutionLimiter.class),
            proactiveChecks);
    }


    @Test
    @DisplayName("위임 실행은 해석한 단계 값을 보내고 요청·제출 시각을 기록한다")
    void sendsResolvedTierAndRecordsRequestAndSubmission() {
        ModelChoice choice = ModelChoice.of("openai-codex", "example-deep", "high");
        when(modelTiers.resolve(any(), any(), any())).thenReturn(new ResolvedModelTier(choice, ModelTier.DEEP));

        run(agent(), null, null);

        HermesRunCommand command = submitted();
        assertThat(command.provider()).isEqualTo(choice.provider());
        assertThat(command.model()).isEqualTo(choice.model());
        assertThat(command.reasoningEffort()).isEqualTo(choice.reasoningEffort());
        verify(executions)
            .start(
                same(user),
                any(),
                any(),
                any(),
                any(),
                any(),
                same(choice),
                any(),
                any(),
                any(),
                eq(ModelTier.DEEP),
                eq(REQUEST_RECEIVED_AT));
        verify(executions).markSubmitted(started);
        verify(executions, never()).markFirstDelta(any());
        var submissionOrder = inOrder(executions, hermes);
        submissionOrder.verify(executions).markSubmitted(started);
        submissionOrder.verify(hermes).submit(any());
    }


    @Test
    @DisplayName("일반 에이전트의 실행은 작업 글을 질문으로 넘겨 Memory 를 조립해 덧붙인 지시 앞에 보낸다")
    void sendsAssembledMemoryForOrdinaryAgent() {
        run(agent(), ADDITION, null);

        verify(contextAssembler).assemble(eq(user), any(), eq("일"));
        String instructions = submitted().instructions();
        assertThat(instructions).contains("GFM", MEMORY).endsWith("\n\n" + ADDITION);
        assertThat(recordedSnapshot().contextChars()).isEqualTo(instructions.length() - ("\n\n" + ADDITION).length());
    }


    @Test
    @DisplayName("살펴보기 트리의 자식은 질문 없이 Memory 를 읽고 기억을 남기는 지침은 받지 않는다")
    void excludesRememberInstructionsFromProactiveChild() {
        when(proactiveChecks.existsByRootExecutionId(10L)).thenReturn(true);

        runner.run(
            user,
            Conversation.startedBy(user.id(), "대화", 2L, Instant.now()),
            agent(),
            "일",
            10L,
            10L,
            RunSession.fresh(),
            execution -> {
            },
            (execution, runId) -> {
            },
            () -> false,
            null,
            null);

        assertThat(submitted().instructions()).contains("GFM", MEMORY).doesNotContain("memory_remember");
        verify(contextAssembler).assemble(eq(user), any(), isNull());
        verify(contextAssembler).withResponseInstructions(any(), eq(false));
    }


    @Test
    @DisplayName("위임 실행이 성공하면 조립한 Memory와 답을 실행 기록에 남긴다")
    void recordsAnswerAsOutputTextForDelegatedRun() {
        Agent agent = agent();
        DelegationKey key = DelegationKey.of("parent-profile", "fos-root", "fos-root", "call_1");

        AgentRun run = run(agent, null, key);

        verify(contextAssembler).assemble(eq(user), any(), any());
        assertThat(submitted().instructions()).contains("GFM", MEMORY);
        verify(executions).complete(same(started), same(agent), any(), any(), eq(ANSWER));
        assertThat(run.execution()).isSameAs(completed);
        assertThat(run.result().succeeded()).as("위임 실행의 성공 여부").isTrue();
        assertThat(run.result().output()).as("위임 실행의 답").isEqualTo(ANSWER);
    }


    private HermesRunCommand submitted() {
        ArgumentCaptor<HermesRunCommand> command = ArgumentCaptor.forClass(HermesRunCommand.class);
        verify(hermes).submit(command.capture());
        return command.getValue();
    }


    private ExecutionContextSnapshot recordedSnapshot() {
        ArgumentCaptor<ExecutionContextSnapshot> snapshot = ArgumentCaptor.forClass(ExecutionContextSnapshot.class);
        verify(executions)
            .start(any(), any(), any(), any(), any(), snapshot.capture(), any(), any(), any(), any(), any(), any());
        return snapshot.getValue();
    }


    private static Agent agent() {
        return Agent.of(
            "runner",
            "실행기",
            "runner-profile",
            "http://agent-runtime.test/p/runner",
            CostMode.API,
            CredentialScope.DEDICATED,
            AgentVisibility.PRIVATE,
            1L,
            Instant.now());
    }


    private AgentRun run(Agent agent, String instructionAddition, DelegationKey delegationKey) {
        return runner.run(
            user,
            Conversation.startedBy(user.id(), "대화", 2L, Instant.now()),
            agent,
            "일",
            null,
            null,
            RunSession.fresh(),
            execution -> {
            },
            (execution, runId) -> {
            },
            () -> false,
            instructionAddition,
            delegationKey);
    }
}
