package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실행이 끝날 때 금액을 저장하고, 그 저장된 금액만 더해 한 달 합계가 나오는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
class UsageCostRecordingTest {

    private static final Long USER_ID = 4_101L;

    /** 요청에 실어 보낸 provider 와 모델이다. 에이전트는 모델을 갖지 않아 검사가 정한다. */
    private static final String PROVIDER = "openai-codex";

    private static final String PRICED_MODEL = "example-model";
    private static final String UNPRICED_MODEL = "gpt-가격표에-없는-모델";
    private static final String UNPRICED_AGENT_CODE = "dad-unpriced";

    @Autowired
    ExecutionRecorder recorder;

    /** 실제로 돈 모델을 읽는 세션 조회를 여기서는 하지 않는다. 기록 규칙만 보는 검사다. */
    @MockitoBean
    HermesRunsClient hermes;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    private Conversation conversation;

    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            Path file = Path.of(UsageCostRecordingTest.class
                    .getResource("/pricing/models-dev-sample.json")
                    .toURI());
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-17T04:00:00Z")));
            return file;
        } catch (URISyntaxException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @BeforeEach
    void startFromAnEmptyLedger() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "저녁 메뉴", null, Instant.now()));
    }

    @Test
    @DisplayName("구독형 바인딩의 실행도 API 가격으로 환산해 저장한다")
    void convertsSubscriptionBindingRunAtApiPriceAndStoresIt() {
        AgentExecution execution = complete(run(1_000L, 800L, 500L));

        assertThat(execution.costMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(execution.estimatedCostMicros()).isEqualTo(16_400L);
        assertThat(execution.costCurrency()).isEqualTo("USD");
        assertThat(execution.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    @DisplayName("최종 결과를 받지 못한 실패 실행은 금액을 남기지 않는다")
    void leavesNoAmountForFailedRunWithoutResult() {
        AgentExecution execution = fail();

        assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.errorCode()).isEqualTo("HERMES_RUN_FAILED");
        assertThat(execution.inputTokens()).isNull();
        assertThat(execution.outputTokens()).isNull();
        assertThat(execution.totalTokens()).isNull();
        assertThat(execution.actualCostMicros()).isNull();
        assertThat(execution.finishedAt()).isNotNull();
        assertThat(execution.estimatedCostMicros()).isNull();
        assertThat(execution.pricingVersion()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "openai-codex, example-model, 800, 16400",
        "anthropic, example-model-large, 800, 41700",
        "openai, gpt-flat, 800, 6000",
        "openai, example-model, , 20000"
    })
    @DisplayName("실패 응답도 실제 provider 의 가격과 캐시 보고 유무에 따라 환산한다")
    void pricesFailedResultUsingServedProviderAndCache(
            String provider, String model, Long cached, long expectedMicros) {
        Agent agent = apiAgent();
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);
        HermesRunResult result = failedRun(new TokenUsage(1_000L, cached, 500L, 1_500L), provider, model);

        AgentExecution failed = recorder.fail(execution, agent, result, requested(agent), "FAILED");
        AgentExecution stored = executions.findById(failed.id()).orElseThrow();

        assertThat(stored.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(stored.errorCode()).isEqualTo("FAILED");
        assertThat(stored.hermesRunId()).isEqualTo("failed-run");
        assertThat(stored.provider()).isEqualTo(provider);
        assertThat(stored.model()).isEqualTo(model);
        assertThat(stored.inputTokens()).isEqualTo(1_000L);
        assertThat(stored.cachedInputTokens()).isEqualTo(cached);
        assertThat(stored.outputTokens()).isEqualTo(500L);
        assertThat(stored.totalTokens()).isEqualTo(1_500L);
        assertThat(stored.estimatedCostMicros()).isEqualTo(expectedMicros);
        assertThat(stored.actualCostMicros()).isEqualTo(expectedMicros);
        assertThat(stored.costCurrency()).isEqualTo("USD");
        assertThat(stored.pricingVersion()).isEqualTo("models.dev@2026-09-17");
        assertThat(stored.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("실패 응답의 모델이 가격표에 없으면 토큰은 남기고 금액은 비운다")
    void preservesTokensWithoutInventingPriceForFailedUnknownModel() {
        Agent agent = apiAgent();
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);

        AgentExecution failed = recorder.fail(
                execution,
                agent,
                failedRun(new TokenUsage(1_000L, 800L, 500L, 1_500L), "openai", UNPRICED_MODEL),
                requested(agent),
                "PROVIDER_BLOCKED");

        assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(failed.model()).isEqualTo(UNPRICED_MODEL);
        assertThat(failed.totalTokens()).isEqualTo(1_500L);
        assertThat(failed.estimatedCostMicros()).isNull();
        assertThat(failed.actualCostMicros()).isNull();
        assertThat(failed.pricingVersion()).isNull();
    }

    @Test
    @DisplayName("실패 응답에 사용량이 없으면 토큰과 금액을 미확인으로 둔다")
    void leavesMissingFailedUsageUnknown() {
        Agent agent = apiAgent();
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);

        AgentExecution failed =
                recorder.fail(execution, agent, failedRun(null, "openai", PRICED_MODEL), requested(agent), "FAILED");

        assertThat(failed.inputTokens()).isNull();
        assertThat(failed.cachedInputTokens()).isNull();
        assertThat(failed.outputTokens()).isNull();
        assertThat(failed.totalTokens()).isNull();
        assertThat(failed.estimatedCostMicros()).isNull();
        assertThat(failed.actualCostMicros()).isNull();
        assertThat(failed.pricingVersion()).isNull();
    }

    @Test
    @DisplayName("실패 응답이 합계 토큰만 알려 주면 입력과 출력을 추측해 환산하지 않는다")
    void leavesFailedCostUnknownWhenOnlyTotalTokensAreReported() {
        Agent agent = apiAgent();
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);

        AgentExecution failed = recorder.fail(
                execution,
                agent,
                failedRun(new TokenUsage(null, null, null, 1_500L), "openai", PRICED_MODEL),
                requested(agent),
                "FAILED");

        assertThat(failed.totalTokens()).isEqualTo(1_500L);
        assertThat(failed.inputTokens()).isNull();
        assertThat(failed.outputTokens()).isNull();
        assertThat(failed.estimatedCostMicros()).isNull();
        assertThat(failed.actualCostMicros()).isNull();
    }

    @Test
    @DisplayName("runtime 없는 실패 응답은 세션의 실제 모델을 읽어 환산한다")
    void resolvesLegacyFailedRuntimeFromSession() {
        Agent agent = subscriptionAgent();
        when(hermes.readSessionRuntime(agent.apiBaseUrl(), agent.hermesProfile(), "failed-session"))
                .thenReturn(new SessionRuntime("example-model-large", "anthropic"));
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);
        HermesRunResult result = new HermesRunResult(
                "failed-run",
                "failed-session",
                "failed",
                null,
                PRICED_MODEL,
                PROVIDER,
                "tool failed",
                new TokenUsage(1_000L, null, 500L, 1_500L));

        AgentExecution failed = recorder.fail(execution, agent, result, requested(agent), "FAILED");

        assertThat(failed.provider()).isEqualTo("anthropic");
        assertThat(failed.model()).isEqualTo("example-model-large");
        assertThat(failed.cachedInputTokens()).isNull();
        assertThat(failed.estimatedCostMicros()).isEqualTo(52_500L);
        assertThat(failed.actualCostMicros()).isNull();
    }

    @Test
    @DisplayName("월 합계는 사용량을 받은 실패와 취소도 더하고 미확인 실패와 실행 중을 구분한다")
    void includesPricedFailureAndCancellationInMonthlyTotals() {
        Agent agent = apiAgent();
        complete(run(1_000L, null, 500L), agent);
        recorder.fail(
                recorder.start(caller(), conversation, agent, null, null, 0L),
                agent,
                failedRun(new TokenUsage(1_000L, 800L, 500L, 1_500L), PROVIDER, PRICED_MODEL),
                requested(agent),
                "FAILED");
        recorder.cancel(
                recorder.start(caller(), conversation, agent, null, null, 0L),
                agent,
                run(1_000L, null, 500L),
                requested(agent));
        fail();
        recorder.start(caller(), conversation, agent, null, null, 0L);

        MonthlyCost cost = executions.sumCostBetween(
                USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));
        MonthlyCostDetail detail = executions.sumCostDetailBetween(
                USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(cost.totalMicros()).isEqualTo(56_400L);
        assertThat(cost.pricedExecutions()).isEqualTo(3L);
        assertThat(cost.unpricedExecutions()).isEqualTo(1L);
        assertThat(detail.actualMicros()).isEqualTo(56_400L);
    }

    private static HermesRunResult failedRun(TokenUsage usage, String provider, String model) {
        return new HermesRunResult(
                "failed-run",
                "failed-session",
                "failed",
                null,
                "requested-model",
                "requested-provider",
                "tool failed",
                usage,
                new SessionRuntime(model, provider));
    }

    @Test
    @DisplayName("한 달 합계는 금액이 잡힌 실행만 더하고 나머지는 따로 센다")
    void monthlyTotalAddsOnlyRunsWithAmountAndCountsRestSeparately() {
        complete(run(1_000L, null, 500L));
        complete(run(1_000L, null, 500L));
        fail();

        MonthlyCost cost = executions.sumCostBetween(
                USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(cost.totalMicros()).isEqualTo(40_000L);
        assertThat(cost.pricedExecutions()).isEqualTo(2L);
        assertThat(cost.unpricedExecutions()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기록이 없는 구간의 합계는 0 이다")
    void totalOfPeriodWithoutRecordsIsZero() {
        MonthlyCost cost = executions.sumCostBetween(
                USER_ID, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-02-01T00:00:00Z"));

        assertThat(cost.totalMicros()).isZero();
        assertThat(cost.pricedExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isZero();
    }

    @Test
    @DisplayName("구독 경로 실행 둘과 API 경로 실행 하나의 합계는 API 경로 하나의 금액과 같다")
    void totalOfTwoSubscriptionRunsAndOneApiRunEqualsAmountOfApiRun() {
        complete(run(1_000L, null, 500L), subscriptionAgent());
        complete(run(1_000L, null, 500L), subscriptionAgent());
        AgentExecution apiExecution = complete(run(1_000L, null, 500L), apiAgent());

        MonthlyCostDetail cost = executions.sumCostDetailBetween(
                USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(cost.actualMicros()).isEqualTo(apiExecution.actualCostMicros());
        assertThat(cost.subscriptionExecutions()).isEqualTo(2L);
    }

    @Test
    @DisplayName("가격표에 없는 모델로 돈 API 경로 실행은 구독 경로로 세지 않는다")
    void apiPathRunWithModelNotInPriceTableIsNotCountedAsSubscription() {
        AgentExecution unpriced = complete(run(1_000L, null, 500L), unpricedApiAgent());

        MonthlyCostDetail cost = executions.sumCostDetailBetween(
                USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(unpriced.estimatedCostMicros()).isNull();
        assertThat(unpriced.actualCostMicros()).isNull();
        assertThat(cost.subscriptionExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isEqualTo(1L);
    }

    private static CurrentUser caller() {
        return new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }

    /**
     * 요청에 실어 보낸 provider 와 모델. 세션 조회가 답하지 않으면 이 값이 기록된다.
     *
     * <p>이 검사는 세션 조회를 하지 않으므로 이 값이 그대로 기록된다. 가격표에 없는 모델을 쓰는 에이전트만
     * 가격표에 없는 모델을 보낸다.
     */
    private static ModelChoice requested(Agent agent) {
        String model = UNPRICED_AGENT_CODE.equals(agent.code()) ? UNPRICED_MODEL : PRICED_MODEL;
        return new ModelChoice(PROVIDER, model, null);
    }

    private AgentExecution complete(HermesRunResult result) {
        return complete(result, subscriptionAgent());
    }

    private AgentExecution complete(HermesRunResult result, Agent agent) {
        AgentExecution execution = recorder.start(caller(), conversation, agent, null, null, 0L);
        return recorder.complete(execution, agent, result, requested(agent));
    }

    private AgentExecution fail() {
        AgentExecution execution = recorder.start(caller(), conversation, subscriptionAgent(), null, null, 0L);
        return recorder.fail(execution, "HERMES_RUN_FAILED");
    }

    private static Agent subscriptionAgent() {
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

    private static Agent apiAgent() {
        return Agent.of(
                "dad-api",
                "Dad API",
                "dad",
                "http://127.0.0.1:1/p/dad",
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID,
                Instant.now());
    }

    /** 가격표에 없는 모델을 쓰는 API 경로 바인딩이다. 두 금액이 모두 비어 있게 된다. */
    private static Agent unpricedApiAgent() {
        return Agent.of(
                UNPRICED_AGENT_CODE,
                "Dad Unpriced",
                "dad",
                "http://127.0.0.1:1/p/dad",
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID,
                Instant.now());
    }

    private static HermesRunResult run(Long input, Long cached, Long output) {
        long total = (input == null ? 0 : input) + (output == null ? 0 : output);
        return HermesRunResult.of(
                "run-1",
                "session-1",
                "completed",
                "메뉴를 골라 봤다",
                "dad",
                null,
                new TokenUsage(input, cached, output, total));
    }
}
