package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillUsageQuery;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.application.RootExecutionQuery;
import com.bifos.assistant.usage.application.UsageSummaryService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos.BreakdownRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** 쌓인 실행을 축별로 묶어 보는 조회를 확인한다. */
@BackendIntegrationTest
class UsageBreakdownTest {

    private static final Long USER_ID = 4_301L;
    private static final Long OTHER_USER_ID = 4_302L;
    private static final Long CONVERSATION_ID = 7_301L;

    /** 대상 달의 한가운데다. 달 경계와 멀어 다른 검사가 경계에 걸리지 않는다. */
    private static final Instant MID_SEPTEMBER = Instant.parse("2026-09-15T03:00:00Z");

    private static final String MONTH = "2026-09";

    /** 실행을 심을 때 쓰는 provider 와 모델이다. 에이전트는 모델을 갖지 않아 실행마다 이 값을 적는다. */
    private static final String PROVIDER = "openai-codex";

    private static final String CAREER_CODE = "breakdown-career";
    private static final String CHORE_CODE = "breakdown-chore";
    private static final String CAREER_MODEL = "example-model";
    private static final String CHORE_MODEL = "example-model-b";
    private static final Map<String, String> MODEL_BY_AGENT =
            Map.of(CAREER_CODE, CAREER_MODEL, CHORE_CODE, CHORE_MODEL);

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentService agentService;

    @Autowired
    ExecutionTreeService trees;

    @Autowired
    UsageSummaryService summaries;

    @Autowired
    RootExecutionQuery rootExecutions;

    @Autowired
    SkillUsageQuery skillUsage;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;
    private Agent career;
    private Agent chore;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        career = agent(CAREER_CODE, "진로 비서");
        chore = agent(CHORE_CODE, "집안일 비서");
        controller = new UsageController(rootExecutions, currentUser, agentService, trees, skillUsage, summaries);
        when(currentUser.require()).thenReturn(new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN));
        // 묶음 합계는 관리자만 받는다. 대역이어도 관리자 확인은 실제 판정을 타게 한다.
        doCallRealMethod().when(currentUser).requireAdmin();
    }

    @Test
    @DisplayName("에이전트 둘의 실행이 섞여 있으면 agent 축이 둘로 묶인다")
    void agentAxisGroupsIntoTwoWhenRunsOfTwoAgentsAreMixed() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        save(career, MID_SEPTEMBER.plusSeconds(60), 90_000L, 6_000L, null);
        save(chore, MID_SEPTEMBER.plusSeconds(120), 60_000L, 500L, null);

        List<BreakdownRow> rows = controller.breakdown("agent", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly(CAREER_CODE, CHORE_CODE);
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
    @DisplayName("모델 축은 provider 와 모델로 묶는다")
    void modelAxisGroupsByProviderAndModel() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        save(chore, MID_SEPTEMBER.plusSeconds(60), 60_000L, 500L, null);

        List<BreakdownRow> rows = controller.breakdown("model", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::label).containsExactly(CAREER_MODEL, CHORE_MODEL);
        assertThat(rows).extracting(BreakdownRow::detail).containsOnly(PROVIDER);
    }

    @Test
    @DisplayName("날짜 축은 하루씩 묶고 이른 날부터 준다")
    void dateAxisGroupsByDayAndReturnsEarlierDaysFirst() {
        save(career, Instant.parse("2026-09-15T03:00:00Z"), 10_000L, 100L, null);
        save(career, Instant.parse("2026-09-15T09:00:00Z"), 20_000L, 100L, null);
        save(career, Instant.parse("2026-09-16T03:00:00Z"), 30_000L, 100L, null);

        List<BreakdownRow> rows = controller.breakdown("day", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("2026-09-15", "2026-09-16");
        assertThat(rows.getFirst().executions()).isEqualTo(2L);
        assertThat(rows.getFirst().estimatedCostMicros()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("날짜 축도 가족이 사는 곳의 달력으로 하루를 끊는다")
    void dateAxisCutsDayByCalendarOfWhereFamilyLives() {
        // 한국 시각으로는 9월 16일 오전 8시다. 세계 표준시로 끊으면 9월 15일이 된다.
        save(career, Instant.parse("2026-09-15T23:00:00Z"), 10_000L, 100L, null);

        List<BreakdownRow> rows = controller.breakdown("day", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("2026-09-16");
    }

    @Test
    @DisplayName("에이전트 행이 없는 실행도 agent 축에 번호로 묶이고 이름은 지운 에이전트다")
    void runWithoutAgentRowGroupsByIdOnAgentAxisAndOtherRowsStay() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);
        save(chore, MID_SEPTEMBER.plusSeconds(60), 60_000L, 500L, null);
        String missingKey = String.valueOf(chore.id());
        agents.deleteById(chore.id());

        List<BreakdownRow> rows = controller.breakdown("agent", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly(CAREER_CODE, missingKey);
        assertThat(rows.getFirst().label()).isEqualTo("진로 비서");
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.label()).as("이름이 없으면 지운 에이전트로 보인다").isEqualTo("지운 에이전트");
            assertThat(row.detail()).isNull();
            assertThat(row.executions()).isOne();
            assertThat(row.estimatedCostMicros()).isEqualTo(60_000L);
        });
    }

    @Test
    @DisplayName("도는 중인 실행은 어느 축에도 세어지지 않는다")
    void runningRunIsCountedOnNoAxis() {
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
    @DisplayName("모르는 축은 400 으로 거절하고 기본값으로 떨어지지 않는다")
    void rejectsUnknownAxisWith400AndDoesNotFallBackToDefault() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);

        assertThatThrownBy(() -> controller.breakdown("workspace", MONTH))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.code().status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("달 형태가 아닌 값도 400 으로 거절한다")
    void rejectsNonMonthFormatWith400Too() {
        assertThatThrownBy(() -> controller.breakdown("agent", "지난달"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("남의 실행은 내 합계에 들어가지 않는다")
    void othersRunsAreNotInMyTotals() {
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
    @DisplayName("달 경계는 가족이 사는 곳의 달력으로 끊긴다")
    void monthBoundaryIsCutByCalendarOfWhereFamilyLives() {
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
    @DisplayName("지문 축은 지문별로 묶고 실행당 평균을 낼 수 있게 준다")
    void fingerprintAxisGroupsByFingerprintAndGivesDataForPerRunAverage() {
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
    @DisplayName("지문이 비어 있는 실행은 지문 축에서 통째로 빠진다")
    void runWithEmptyFingerprintDropsWholeFromFingerprintAxis() {
        save(career, MID_SEPTEMBER, 50_000L, 500L, null);
        save(career, MID_SEPTEMBER.plusSeconds(60), 70_000L, 700L, "a3f2");

        List<BreakdownRow> rows = controller.breakdown("fingerprint", MONTH).rows();

        assertThat(rows).extracting(BreakdownRow::key).containsExactly("a3f2");
        assertThat(rows.getFirst().executions()).isOne();
    }

    @Test
    @DisplayName("기록이 없는 달은 빈 줄 목록을 준다")
    void monthWithoutRecordsGivesEmptyRowList() {
        assertThat(controller.breakdown("agent", "2020-01").rows()).isEmpty();
        assertThat(controller.breakdown("fingerprint", "2020-01").rows()).isEmpty();
    }

    @Test
    @DisplayName("구독 경로만 있는 묶음은 실제 청구액을 0 으로 채우지 않는다")
    void groupWithOnlySubscriptionPathDoesNotFillActualBilledWithZero() {
        save(career, MID_SEPTEMBER, 14_000_000L, 7_000L, null);

        assertThat(controller.breakdown("agent", MONTH).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.actualCostMicros()).isNull());
    }

    private AgentExecution save(
            Agent agent, Instant startedAt, Long estimatedMicros, Long contextChars, String fingerprint) {
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
                .provider(PROVIDER)
                .model(MODEL_BY_AGENT.get(agent.code()))
                .costMode(agent.costMode())
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(startedAt);
    }

    private Agent agent(String code, String name) {
        return agents.findByCode(code)
                .orElseGet(() -> agents.save(Agent.of(
                        code,
                        name,
                        code,
                        "http://127.0.0.1:1/p/" + code,
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID,
                        Instant.now())));
    }
}
