package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실행이 끝날 때 금액을 저장하고, 그 저장된 금액만 더해 한 달 합계가 나오는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
class UsageCostRecordingTest {

    private static final Long USER_ID = 4_101L;


    @Autowired ExecutionRecorder recorder;

    /** 실제로 돈 모델을 읽는 세션 조회를 여기서는 하지 않는다. 기록 규칙만 보는 검사다. */
    @MockitoBean HermesRunsClient hermes;
    @Autowired AgentExecutionRepository executions;
    @Autowired ConversationRepository conversations;

    private Conversation conversation;

    @DynamicPropertySource
    static void pointAtTheSampleCatalog(DynamicPropertyRegistry registry) {
        registry.add("assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            Path file =
                    Path.of(UsageCostRecordingTest.class.getResource("/pricing/models-dev-sample.json").toURI());
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-17T04:00:00Z")));
            return file;
        } catch (URISyntaxException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @BeforeEach
    void startFromAnEmptyLedger() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "저녁 메뉴", null));
    }

    @Test
    void 구독형_바인딩의_실행도_API_가격으로_환산해_저장한다() {
        AgentExecution execution =
                complete(run(1_000L, 800L, 500L));

        assertThat(execution.costMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(execution.estimatedCostMicros()).isEqualTo(16_400L);
        assertThat(execution.costCurrency()).isEqualTo("USD");
        assertThat(execution.pricingVersion()).isEqualTo("models.dev@2026-09-17");
    }

    @Test
    void 실패한_실행은_금액을_남기지_않는다() {
        AgentExecution execution =
                fail();

        assertThat(execution.estimatedCostMicros()).isNull();
        assertThat(execution.pricingVersion()).isNull();
    }

    @Test
    void 한_달_합계는_금액이_잡힌_실행만_더하고_나머지는_따로_센다() {
        complete(run(1_000L, null, 500L));
        complete(run(1_000L, null, 500L));
        fail();

        MonthlyCost cost =
                executions.sumCostBetween(
                        USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(cost.totalMicros()).isEqualTo(40_000L);
        assertThat(cost.pricedExecutions()).isEqualTo(2L);
        assertThat(cost.unpricedExecutions()).isEqualTo(1L);
    }

    @Test
    void 기록이_없는_구간의_합계는_0_이다() {
        MonthlyCost cost =
                executions.sumCostBetween(
                        USER_ID, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-02-01T00:00:00Z"));

        assertThat(cost.totalMicros()).isZero();
        assertThat(cost.pricedExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isZero();
    }

    @Test
    void 구독_경로_실행_둘과_API_경로_실행_하나의_합계는_API_경로_하나의_금액과_같다() {
        complete(run(1_000L, null, 500L), subscriptionAgent());
        complete(run(1_000L, null, 500L), subscriptionAgent());
        AgentExecution apiExecution = complete(run(1_000L, null, 500L), apiAgent());

        MonthlyCostDetail cost =
                executions.sumCostDetailBetween(
                        USER_ID, Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600));

        assertThat(cost.actualMicros()).isEqualTo(apiExecution.actualCostMicros());
        assertThat(cost.subscriptionExecutions()).isEqualTo(2L);
    }

    @Test
    void 가격표에_없는_모델로_돈_API_경로_실행은_구독_경로로_세지_않는다() {
        AgentExecution unpriced = complete(run(1_000L, null, 500L), unpricedApiAgent());

        MonthlyCostDetail cost =
                executions.sumCostDetailBetween(
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
     * <p>이 검사는 세션 조회를 하지 않으므로 그 에이전트의 1순위를 그대로 요청 값으로 둔다.
     */
    private static ModelOption requested(Agent agent) {
        return new ModelOption(agent.provider(), agent.model());
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
                "openai-codex",
                "gpt-5.6-sol",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID);
    }

    private static Agent apiAgent() {
        return Agent.of(
                "dad-api",
                "Dad API",
                "dad",
                "http://127.0.0.1:1/p/dad",
                "openai-codex",
                "gpt-5.6-sol",
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID);
    }

    /** 가격표에 없는 모델을 쓰는 API 경로 바인딩이다. 두 금액이 모두 비어 있게 된다. */
    private static Agent unpricedApiAgent() {
        return Agent.of(
                "dad-unpriced",
                "Dad Unpriced",
                "dad",
                "http://127.0.0.1:1/p/dad",
                "openai-codex",
                "gpt-가격표에-없는-모델",
                CostMode.API,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID);
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
