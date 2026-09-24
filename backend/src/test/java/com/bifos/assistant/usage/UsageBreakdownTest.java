package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos;
import com.bifos.assistant.usage.presentation.UsageDtos.BreakdownRow;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/** 쌓인 실행을 축별로 묶어 보는 조회를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class UsageBreakdownTest {

    private static final Long USER_ID = 4_301L;
    private static final Long OTHER_USER_ID = 4_302L;
    private static final Long CONVERSATION_ID = 7_301L;

    /** 대상 달의 한가운데다. 달 경계와 멀어 다른 검사가 경계에 걸리지 않는다. */
    private static final Instant MID_SEPTEMBER = Instant.parse("2026-09-15T03:00:00Z");

    private static final String MONTH = "2026-09";

    @Autowired AgentExecutionRepository executions;
    @Autowired AgentRepository agents;
    @Autowired AgentService agentService;
    @Autowired ExecutionTreeService trees;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;
    private Agent career;
    private Agent chore;

    @BeforeEach
    void 준비한다() {
        executions.deleteAll();
        career = agent("breakdown-career", "진로 비서", "example-model");
        chore = agent("breakdown-chore", "집안일 비서", "example-model-b");
        controller = new UsageController(executions, currentUser, agentService, trees);
        when(currentUser.require())
                .thenReturn(new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN));
    }

    @Test
    void 에이전트_둘의_실행이_섞여_있으면_agent_축이_둘로_묶인다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        save(career, MID_SEPTEMBER.plusSeconds(60), 90_000L, 6_000L, null);
        save(chore, MID_SEPTEMBER.plusSeconds(120), 60_000L, 500L, null);

        List<BreakdownRow> rows = controller.breakdown("agent", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("breakdown-career", "breakdown-chore");
        assertThat(rows.getFirst()).satisfies(row -> {
            assertThat(row.label()).isEqualTo("진로 비서");
            assertThat(row.executions()).isEqualTo(2L);
            assertThat(row.estimatedCostMicros()).isEqualTo(14_090_000L);
            assertThat(row.inputTokens()).isEqualTo(2_000L);
            assertThat(row.outputTokens()).isEqualTo(1_000L);
            assertThat(row.avgContextChars()).isEqualTo(6_500L);
        });
        assertThat(rows.get(1).executions()).isOne();
        assertThat(rows.get(1).estimatedCostMicros()).isEqualTo(60_000L);
    }

    @Test
    void 모델_축은_provider_와_모델로_묶는다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        save(chore, MID_SEPTEMBER.plusSeconds(60), 60_000L, 500L, null);

        List<BreakdownRow> rows = controller.breakdown("model", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::label).containsExactly("example-model", "example-model-b");
        assertThat(rows).extracting(BreakdownRow::detail).containsOnly("openai-codex");
    }

    @Test
    void 날짜_축은_하루씩_묶고_이른_날부터_준다() {
        save(career, Instant.parse("2026-09-15T03:00:00Z"), 10_000L, 100L, null);
        save(career, Instant.parse("2026-09-15T09:00:00Z"), 20_000L, 100L, null);
        save(career, Instant.parse("2026-09-16T03:00:00Z"), 30_000L, 100L, null);

        List<BreakdownRow> rows = controller.breakdown("day", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("2026-09-15", "2026-09-16");
        assertThat(rows.getFirst().executions()).isEqualTo(2L);
        assertThat(rows.getFirst().estimatedCostMicros()).isEqualTo(30_000L);
    }

    @Test
    void 날짜_축도_가족이_사는_곳의_달력으로_하루를_끊는다() {
        // 한국 시각으로는 9월 16일 오전 8시다. 세계 표준시로 끊으면 9월 15일이 된다.
        save(career, Instant.parse("2026-09-15T23:00:00Z"), 10_000L, 100L, null);

        List<BreakdownRow> rows = controller.breakdown("day", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("2026-09-16");
    }

    @Test
    void 도는_중인_실행은_어느_축에도_세어지지_않는다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        executions.save(builder(USER_ID, career, MID_SEPTEMBER.plusSeconds(60))
                .status(ExecutionStatus.RUNNING)
                .build());

        assertThat(controller.breakdown("agent", MONTH).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.executions()).isOne());
        assertThat(controller.breakdown("day", MONTH).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.executions()).isOne());
    }

    @Test
    void 모르는_축은_400_으로_거절하고_기본값으로_떨어지지_않는다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);

        assertThatThrownBy(() -> controller.breakdown("workspace", MONTH))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.code().status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void 달_형태가_아닌_값도_400_으로_거절한다() {
        assertThatThrownBy(() -> controller.breakdown("agent", "지난달"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void 남의_실행은_내_합계에_들어가지_않는다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        executions.save(builder(OTHER_USER_ID, career, MID_SEPTEMBER.plusSeconds(60))
                .cost(new ExecutionCost(99_000_000L, null, "USD", "models.dev@2026-09-17"))
                .tokens(1_000L, null, 500L, 1_500L)
                .build());

        List<BreakdownRow> rows = controller.breakdown("agent", MONTH).rows();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.executions()).isOne();
            assertThat(row.estimatedCostMicros()).isEqualTo(14_000_000L);
        });
    }

    @Test
    void 달_경계는_가족이_사는_곳의_달력으로_끊긴다() {
        // 한국 시각으로 9월 30일 23시와 10월 1일 1시다.
        save(career, Instant.parse("2026-09-30T14:00:00Z"), 10_000L, 100L, null);
        save(career, Instant.parse("2026-09-30T16:00:00Z"), 20_000L, 100L, null);

        assertThat(controller.breakdown("agent", MONTH).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.estimatedCostMicros()).isEqualTo(10_000L));
        assertThat(controller.breakdown("agent", "2026-10").rows())
                .singleElement()
                .satisfies(row -> assertThat(row.estimatedCostMicros()).isEqualTo(20_000L));
    }

    @Test
    void 지문_축은_지문별로_묶고_실행당_평균을_낼_수_있게_준다() {
        save(career, MID_SEPTEMBER, 50_000L, 500L, "a3f2");
        save(career, MID_SEPTEMBER.plusSeconds(60), 70_000L, 700L, "a3f2");
        save(career, MID_SEPTEMBER.minusSeconds(86_400), 30_000L, 300L, "8c11");

        List<BreakdownRow> rows = controller.breakdown("fingerprint", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("a3f2", "8c11");
        assertThat(rows.getFirst()).satisfies(row -> {
            assertThat(row.executions()).isEqualTo(2L);
            assertThat(row.estimatedCostMicros()).isEqualTo(120_000L);
            assertThat(row.firstSeenAt()).isEqualTo(MID_SEPTEMBER);
            assertThat(row.lastSeenAt()).isEqualTo(MID_SEPTEMBER.plusSeconds(60));
        });
    }

    @Test
    void 지문이_비어_있는_실행은_지문_축에서_통째로_빠진다() {
        save(career, MID_SEPTEMBER, 50_000L, 500L, null);
        save(career, MID_SEPTEMBER.plusSeconds(60), 70_000L, 700L, "a3f2");

        List<BreakdownRow> rows = controller.breakdown("fingerprint", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("a3f2");
        assertThat(rows.getFirst().executions()).isOne();
    }

    @Test
    void 기록이_없는_달은_빈_줄_목록을_준다() {
        assertThat(controller.breakdown("agent", "2020-01").rows()).isEmpty();
        assertThat(controller.breakdown("fingerprint", "2020-01").rows()).isEmpty();
    }

    @Test
    void 구독_경로만_있는_묶음은_실제_청구액을_0_으로_채우지_않는다() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);

        assertThat(controller.breakdown("agent", MONTH).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.actualCostMicros()).isNull());
    }

    private AgentExecution save(Agent agent, Instant startedAt, Long estimatedMicros, Long contextChars,
            String fingerprint) {
        return executions.save(builder(USER_ID, agent, startedAt)
                .tokens(1_000L, null, 500L, 1_500L)
                .cost(new ExecutionCost(estimatedMicros, null, "USD", "models.dev@2026-09-17"))
                .contextChars(contextChars)
                .runtimeFingerprint(fingerprint)
                .build());
    }

    private static AgentExecution.Builder builder(Long userId, Agent agent, Instant startedAt) {
        return AgentExecution.builder()
                .userId(userId)
                .conversationId(CONVERSATION_ID)
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .provider(agent.provider())
                .model(agent.model())
                .costMode(agent.costMode())
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(startedAt);
    }

    private Agent agent(String code, String name, String model) {
        return agents.findByCode(code)
                .orElseGet(() -> agents.save(Agent.of(
                        code,
                        name,
                        code,
                        "http://127.0.0.1:1/p/" + code,
                        "openai-codex",
                        model,
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID)));
    }
}
