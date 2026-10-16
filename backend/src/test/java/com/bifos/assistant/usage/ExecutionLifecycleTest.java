package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 실행 시작과 종료가 하나의 기록을 상태 전이하는지 확인한다. */
@BackendIntegrationTest
class ExecutionLifecycleTest {

    private static final Long USER_ID = 4_102L;

    /** 요청에 실어 보낸 provider 와 모델이다. 에이전트는 모델을 갖지 않아 검사가 정한다. */
    private static final String REQUESTED_PROVIDER = "anthropic";

    private static final String REQUESTED_MODEL = "example-model-large";

    /** 실행 줄 어디에도 남으면 안 되는 개인 기록을 흉내낸 문자열이다. */
    private static final String SECRET_MEMORY = "아빠는 매주 목요일에 병원에 간다";

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    StubHermesRunsClient hermes;

    private Conversation conversation;


    @BeforeEach
    void setUp() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "실행", null, Instant.now()));
    }


    @Test
    @DisplayName("시작한 실행은 종료 정보 없이 RUNNING이고 부모와 루트를 저장한다")
    void startedRunIsRunningWithoutEndInfoAndStoresParentAndRoot() {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), 12L, 3L, 0L);

        assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.finishedAt()).isNull();
        assertThat(execution.latencyMs()).isNull();
        assertThat(execution.parentExecutionId()).isEqualTo(12L);
        assertThat(execution.rootExecutionId()).isEqualTo(3L);
    }


    @Test
    @DisplayName("완료는 같은 줄에 토큰과 금액을 갱신한다")
    void completionUpdatesTokensAndAmountOnSameRow() {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        Long id = execution.id();

        AgentExecution completed = recorder.complete(execution, agent(), result(), requested());

        assertThat(completed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(saved.inputTokens()).isEqualTo(120L);
            assertThat(saved.estimatedCostMicros()).isNull();
            assertThat(saved.finishedAt()).isNotNull();
            assertThat(saved.latencyMs()).isNotNull();
        });
    }


    @Test
    @DisplayName("실패와 run 번호 연결은 같은 줄을 갱신한다")
    void failureAndRunIdLinkUpdateSameRow() {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        Long id = execution.id();

        recorder.attachRunId(execution, "run-1");
        AgentExecution failed = recorder.fail(execution, "HERMES_UNAVAILABLE");

        assertThat(failed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.hermesRunId()).isEqualTo("run-1");
            assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(saved.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }


    @ParameterizedTest
    @CsvSource({
        "run-provider, run-model, session-provider, session-model, run-provider, run-model",
        "run-provider, , session-provider, session-model, session-provider, session-model",
        "run-provider, , , session-model, run-provider, session-model",
        " , run-model, , , anthropic, run-model",
        " , , , , anthropic, example-model-large"
    })
    @DisplayName("완전한 실행 runtime 짝을 우선하고 부족한 칸은 세션과 요청 순서로 채운다")
    void preservesRuntimePairAndFallbackOrder(
        String runProvider, String runModel, String sessionProvider, String sessionModel,
        String expectedProvider, String expectedModel) {
        hermes.willReportSessionRuntime(new SessionRuntime(sessionModel, sessionProvider));
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        HermesRunResult result = new HermesRunResult(
            "runtime-run", "runtime-session", "completed", "답", null, null, null,
            new TokenUsage(120L, 80L, 40L, 160L), new SessionRuntime(runModel, runProvider));

        recorder.complete(execution, agent(), result, requested());

        assertThat(executions.findById(execution.id()).orElseThrow()).satisfies(saved -> {
            assertThat(saved.provider()).isEqualTo(expectedProvider);
            assertThat(saved.model()).isEqualTo(expectedModel);
            assertThat(saved.totalTokens()).isEqualTo(160L);
            assertThat(saved.estimatedCostMicros()).isNull();
            assertThat(saved.pricingVersion()).isNull();
        });
    }


    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"SUCCEEDED", "CANCELLED"})
    @DisplayName("완료와 취소는 종료 상태와 답을 같은 실행 줄에 저장한다")
    void storesTerminalStatusAndOutputOnSameRow(ExecutionStatus status) {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        if (status == ExecutionStatus.SUCCEEDED) {
            recorder.complete(execution, agent(), result(), requested(), "저장할 답");
        } else {
            recorder.cancel(execution, agent(), result(), requested(), "저장할 답");
        }

        assertThat(executions.count()).isOne();
        assertThat(executions.findById(execution.id()).orElseThrow()).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(status);
            assertThat(saved.outputText()).isEqualTo("저장할 답");
            assertThat(saved.hermesRunId()).isEqualTo("run-1");
            assertThat(saved.totalTokens()).isEqualTo(160L);
            assertThat(saved.finishedAt()).isNotNull();
        });
    }


    @Test
    @DisplayName("취소한 실행의 후속 사용량 저장은 기존 종료 시각을 보존한다")
    void preservesCancellationTimeWhenUsageArrivesLater() {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        Instant cancelledAt = Instant.parse("2026-10-01T00:00:00Z");
        execution.markCancelled(cancelledAt);
        executions.save(execution);

        recorder.cancel(execution, agent(), result(), requested(), "멈춘 답");

        assertThat(executions.findById(execution.id()).orElseThrow()).satisfies(saved -> {
            assertThat(saved.finishedAt()).isEqualTo(cancelledAt);
            assertThat(saved.status()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(saved.totalTokens()).isEqualTo(160L);
            assertThat(saved.outputText()).isEqualTo("멈춘 답");
        });
    }


    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"FAILED", "CANCELLED"})
    @DisplayName("최종 결과가 없는 실패와 취소는 기존 사용량과 답을 보존한다")
    void preservesRecordedUsageWhenFinalResultIsMissing(ExecutionStatus status) {
        AgentExecution execution = recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);
        recorder.complete(execution, agent(), result(), requested(), "기존 답");
        if (status == ExecutionStatus.FAILED) {
            recorder.fail(execution, agent(), null, requested(), "FAILED");
        } else {
            recorder.cancel(execution, agent(), null, requested(), "새 답");
        }

        assertThat(executions.findById(execution.id()).orElseThrow()).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(status);
            assertThat(saved.totalTokens()).isEqualTo(160L);
            assertThat(saved.outputText()).isEqualTo("기존 답");
        });
    }


    @Test
    @DisplayName("같은 instructions 는 같은 해시로 적히고 본문은 어디에도 저장되지 않는다")
    void sameInstructionsGiveSameHashAndBodyIsStoredNowhere() {
        AssembledContext context = contextOf(SECRET_MEMORY);

        AgentExecution execution = recorder.start(
            user(),
            conversation.executionConversation(),
            agent(),
            null,
            null,
            new ExecutionContextSnapshot(context.chars(), null, context.instructionsHash()));

        AgentExecution saved = executions.findById(execution.id()).orElseThrow();
        assertThat(saved.instructionsHash())
            .isEqualTo(contextOf(SECRET_MEMORY).instructionsHash())
            .hasSize(32);
        assertThat(stringColumnsOf(saved))
            .noneSatisfy(value -> assertThat(value).contains(SECRET_MEMORY));
    }


    @Test
    @DisplayName("다른 instructions 는 다른 해시로 적힌다")
    void differentInstructionsGiveDifferentHash() {
        AssembledContext one = contextOf(SECRET_MEMORY);
        AssembledContext other = contextOf(SECRET_MEMORY + " 그리고 하나 더");

        assertThat(one.instructionsHash()).isNotEqualTo(other.instructionsHash());
    }


    @Test
    @DisplayName("문맥이 없으면 해시 칸도 비운다")
    void leavesHashColumnEmptyWhenNoContext() {
        assertThat(AssembledContext.empty().instructionsHash()).isNull();
        assertThat(new AssembledContext("", 0).instructionsHash()).isNull();

        AgentExecution execution = recorder.start(
            user(),
            conversation.executionConversation(),
            agent(),
            null,
            null,
            new ExecutionContextSnapshot(0L, null, AssembledContext.empty().instructionsHash()));

        assertThat(executions.findById(execution.id()).orElseThrow().instructionsHash())
            .isNull();
    }


    @Test
    @DisplayName("설정 지문은 읽는 경로가 없어 비어 있다")
    void configFingerprintIsEmptyBecauseNothingReadsIt() {
        AssembledContext context = contextOf(SECRET_MEMORY);

        AgentExecution execution = recorder.start(
            user(),
            conversation.executionConversation(),
            agent(),
            null,
            null,
            new ExecutionContextSnapshot(context.chars(), null, context.instructionsHash()));

        assertThat(executions.findById(execution.id()).orElseThrow().runtimeFingerprint())
            .isNull();
    }


    @Test
    @DisplayName("실행 중인 줄은 가격 미확인 실행으로 세지 않는다")
    void runningRowIsNotCountedAsPriceUnknownRun() {
        recorder.start(user(), conversation.executionConversation(), agent(), null, null, 0L);

        MonthlyCost cost = executions.sumCostBetween(
            USER_ID, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        assertThat(cost.totalMicros()).isZero();
        assertThat(cost.pricedExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isZero();
    }


    private static AssembledContext contextOf(String memoryContent) {
        String instructions = "# 지금 묻는 사람에 대해 아는 것\n\n- " + memoryContent;
        return new AssembledContext(instructions, instructions.length());
    }


    /**
     * 저장된 실행 줄의 문자열 칸을 모두 모은다. 어느 칸에도 본문이 남지 않은 것을 확인하기 위해서다.
     *
     * <p>{@code AgentExecution} 에 문자열 칸을 더하면 여기도 더한다. 빠뜨리면 그 칸에 본문이 남아도
     * 이 단언이 보지 못한 채 테스트가 통과한다.
     */
    private static List<String> stringColumnsOf(AgentExecution execution) {
        return Stream.of(
            execution.profileName(),
            execution.hermesRunId(),
            execution.provider(),
            execution.model(),
            execution.costMode().name(),
            execution.status().name(),
            execution.errorCode(),
            execution.runtimeFingerprint(),
            execution.instructionsHash(),
            execution.costCurrency(),
            execution.pricingVersion())
            .filter(Objects::nonNull)
            .toList();
    }


    private static CurrentUser user() {
        return new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }


    private static Agent agent() {
        return Agent.of(
            "dad",
            "Dad",
            "dad",
            "http://127.0.0.1:1/p/dad",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            USER_ID,
            Instant.now());
    }


    /** 요청에 실어 보낸 provider 와 모델. 세션 조회가 답하지 않으면 이 값이 기록된다. */
    private static ModelChoice requested() {
        return new ModelChoice(REQUESTED_PROVIDER, REQUESTED_MODEL, null);
    }


    private static HermesRunResult result() {
        return HermesRunResult.of(
            "run-1",
            "session-1",
            "completed",
            "끝",
            "example-model-large",
            "anthropic",
            new TokenUsage(120L, 80L, 40L, 160L));
    }
}
