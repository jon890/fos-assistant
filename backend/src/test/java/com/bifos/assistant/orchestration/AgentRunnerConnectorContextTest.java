package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ResolvedModelTier;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.AgentRunner;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.orchestration.domain.RunSession;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 커넥터 에이전트의 실행이 Memory 문맥을 받지 않는 것을 고정한다(ADR-045).
 *
 * <p>외부 서비스의 글을 읽는 실행에 개인 Memory 를 넣으면 그 글이 모델을 속여 Memory 를 밖으로 내보낼 수 있다.
 * 일반 에이전트의 실행은 그대로 Memory 를 받는다.
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
    private static final Instant REQUEST_RECEIVED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private final CurrentUser user = new CurrentUser(1L, "runner@example.com", "가", 1L, UserRole.MEMBER);
    private final AgentExecution started = mock(AgentExecution.class);
    private final AgentExecution completed = mock(AgentExecution.class);
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        when(contextAssembler.assemble(user)).thenReturn(new AssembledContext(MEMORY, MEMORY.length()));
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
                new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 100),
                modelTiers,
                Clock.fixed(REQUEST_RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Flow와 위임 실행은 해석한 단계 값을 보내고 요청·제출 시각을 기록한다")
    void sendsResolvedTierAndRecordsRequestAndSubmission() {
        ModelChoice choice = ModelChoice.of("openai-codex", "gpt-6.1-sol", "high");
        when(modelTiers.resolve(any(), any(), any())).thenReturn(new ResolvedModelTier(choice, ModelTier.DEEP));

        run(agent(), null, null);

        HermesRunCommand command = submitted();
        assertThat(command.provider()).isEqualTo(choice.provider());
        assertThat(command.model()).isEqualTo(choice.model());
        assertThat(command.reasoningEffort()).isEqualTo(choice.reasoningEffort());
        verify(executions).start(
                same(user), any(), any(), any(), any(), any(), same(choice), any(), any(), any(),
                eq(ModelTier.DEEP), eq(REQUEST_RECEIVED_AT));
        verify(executions).markSubmitted(started);
        verify(executions, never()).markFirstDelta(any());
        var submissionOrder = inOrder(executions, hermes);
        submissionOrder.verify(executions).markSubmitted(started);
        submissionOrder.verify(hermes).submit(any());
    }

    @Test
    @DisplayName("커넥터 에이전트의 실행은 Memory 를 조립하지 않고 덧붙인 지시만 보낸다")
    void sendsOnlyInstructionAdditionForConnectorAgent() {
        run(connectorAgent(), ADDITION, null);

        verify(contextAssembler, never()).assemble(any());
        assertThat(submitted().instructions()).as("Hermes 에 보낸 instructions").isEqualTo(ADDITION);
        ExecutionContextSnapshot snapshot = recordedSnapshot();
        assertThat(snapshot.contextChars()).as("실행 줄에 적는 문맥 길이").isZero();
        assertThat(snapshot.instructionsHash()).as("실행 줄에 적는 문맥 지문").isNull();
    }

    @Test
    @DisplayName("커넥터 에이전트의 실행에 덧붙일 지시가 없으면 instructions 를 비워 보낸다")
    void sendsNoInstructionsForConnectorAgentWithoutAddition() {
        run(connectorAgent(), null, null);

        verify(contextAssembler, never()).assemble(any());
        assertThat(submitted().instructions()).as("Hermes 에 보낸 instructions").isNull();
    }

    @Test
    @DisplayName("일반 에이전트의 실행은 Memory 를 조립해 덧붙인 지시 앞에 보낸다")
    void sendsAssembledMemoryForOrdinaryAgent() {
        run(agent(), ADDITION, null);

        verify(contextAssembler).assemble(user);
        assertThat(submitted().instructions()).as("Hermes 에 보낸 instructions").isEqualTo(MEMORY + "\n\n" + ADDITION);
        assertThat(recordedSnapshot().contextChars()).as("실행 줄에 적는 문맥 길이").isEqualTo((long) MEMORY.length());
    }

    @Test
    @DisplayName("위임받은 커넥터 에이전트의 실행이 성공하면 Memory 없이도 답이 output_text 로 넘어간다")
    void recordsAnswerAsOutputTextForDelegatedConnectorRun() {
        Agent agent = connectorAgent();
        DelegationKey key = DelegationKey.of("parent-profile", "fos-root", "fos-root", "call_1");

        AgentRunner.Run run = run(agent, null, key);

        verify(contextAssembler, never()).assemble(any());
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
        verify(executions).start(any(), any(), any(), any(), any(), snapshot.capture(), any(), any(), any(), any(), any(), any());
        return snapshot.getValue();
    }

    private static Agent connectorAgent() {
        Agent agent = agent();
        agent.markConnectorManaged();
        return agent;
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
                1L);
    }

    private AgentRunner.Run run(Agent agent, String instructionAddition, DelegationKey delegationKey) {
        return runner.run(
                user,
                Conversation.startedBy(user.id(), "대화", 2L),
                agent,
                "일",
                null,
                null,
                RunSession.fresh(),
                execution -> {},
                (execution, runId) -> {},
                () -> false,
                instructionAddition,
                delegationKey);
    }
}
