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
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillUsageQuery;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.type.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.application.RootExecutionQuery;
import com.bifos.assistant.usage.application.UsageSummaryService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos;
import com.bifos.assistant.shared.domain.type.UserRole;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 사용량 목록이 자식 여부를 어떻게 붙이는지 본다.
 *
 * <p>질의 수를 세려고 통계를 켠 별도의 문맥으로 띄운다. 세는 것은 <b>목록 길이가 늘어도 자식 확인이 한
 * 번인가</b> 하나다. 목록 조회 전체의 질의 수가 아니다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class UsageControllerTest {

    private static final Long USER_ID = 4_401L;

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

    @Autowired
    EntityManagerFactory entityManagers;

    @Autowired
    ExecutionSkillUseRepository skillUses;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;
    private Agent agent;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        agent = agents.findByCode("list-dad")
                .orElseGet(() -> agents.save(Agent.of(
                        "list-dad",
                        "목록 아빠",
                        "dad",
                        "http://127.0.0.1:1/p/dad",
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID,
                        Instant.now())));
        controller = new UsageController(rootExecutions, currentUser, agentService, trees, skillUsage, summaries);
        // 대역이어도 관리자 확인은 실제 판정을 탄다. 그래야 MEMBER 역할의 거절을 볼 수 있다.
        doCallRealMethod().when(currentUser).requireAdmin();
        signInAs(UserRole.ADMIN);
    }

    /** 같은 사용자를 이 역할로 로그인한 것으로 둔다. 자료는 같고 요청자의 역할만 다르게 하기 위해서다. */
    private void signInAs(UserRole role) {
        when(currentUser.require()).thenReturn(new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, role));
    }

    @Test
    @DisplayName("MEMBER 역할의 실행 한 줄에는 내부 값이 비고 오류 코드와 걸린 시간은 남는다")
    void memberExecutionRowHasNoInternalValuesButKeepsErrorCodeAndLatency() {
        AgentExecution failed = detailedExecution();
        signInAs(UserRole.MEMBER);

        UsageDtos.ExecutionView view = controller.myExecutions(50).stream()
                .filter(row -> row.id().equals(failed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(view.agentCode()).as("agentCode").isNull();
        assertThat(view.provider()).as("provider").isNull();
        assertThat(view.model()).as("model").isNull();
        assertThat(view.reasoningEffort()).as("reasoningEffort").isNull();
        assertThat(view.costMode()).as("costMode").isNull();
        assertThat(view.runtimeFingerprint()).as("runtimeFingerprint").isNull();
        assertThat(view.instructionsHash()).as("instructionsHash").isNull();
        assertThat(view.costCurrency()).as("costCurrency").isNull();
        assertThat(view.pricingVersion()).as("pricingVersion").isNull();
        assertThat(view.inputTokens()).as("inputTokens").isNull();
        assertThat(view.cachedInputTokens()).as("cachedInputTokens").isNull();
        assertThat(view.outputTokens()).as("outputTokens").isNull();
        assertThat(view.totalTokens()).as("totalTokens").isNull();
        assertThat(view.contextChars()).as("contextChars").isNull();
        assertThat(view.contextOmittedItems()).as("contextOmittedItems").isNull();
        assertThat(view.estimatedCostMicros()).as("estimatedCostMicros").isNull();
        assertThat(view.actualCostMicros()).as("actualCostMicros").isNull();
        // 화면이 MEMBER 역할에게도 그리거나 분기에 쓰는 값은 남는다.
        assertThat(view.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(view.latencyMs()).isEqualTo(1_234L);
        assertThat(view.agentName()).isEqualTo("목록 아빠");
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("ADMIN 역할의 실행 한 줄에는 내부 값이 그대로 실린다")
    void adminExecutionRowKeepsInternalValues() {
        AgentExecution failed = detailedExecution();

        UsageDtos.ExecutionView view = controller.myExecutions(50).stream()
                .filter(row -> row.id().equals(failed.id()))
                .findFirst()
                .orElseThrow();

        assertThat(view.agentCode()).isEqualTo("list-dad");
        assertThat(view.provider()).isEqualTo("example-provider");
        assertThat(view.model()).isEqualTo("example-model-large");
        assertThat(view.reasoningEffort()).isEqualTo("high");
        assertThat(view.costMode()).isEqualTo("SUBSCRIPTION");
        assertThat(view.runtimeFingerprint()).isEqualTo("fingerprint-1");
        assertThat(view.instructionsHash()).isEqualTo("hash-1");
        assertThat(view.costCurrency()).isEqualTo("USD");
        assertThat(view.pricingVersion()).isEqualTo("2026-09");
        assertThat(view.inputTokens()).isEqualTo(100L);
        assertThat(view.cachedInputTokens()).isEqualTo(40L);
        assertThat(view.outputTokens()).isEqualTo(20L);
        assertThat(view.totalTokens()).isEqualTo(120L);
        assertThat(view.contextChars()).isEqualTo(3_000L);
        assertThat(view.contextOmittedItems()).isEqualTo(2);
        assertThat(view.estimatedCostMicros()).isEqualTo(5_000L);
        assertThat(view.actualCostMicros()).isEqualTo(4_000L);
        assertThat(view.errorCode()).isEqualTo("PROVIDER_BLOCKED");
        assertThat(view.latencyMs()).isEqualTo(1_234L);
    }

    @Test
    @DisplayName("MEMBER 역할의 이번 달 합계에는 실행 건수만 있고 금액과 건수 구분은 빈다")
    void memberMonthlyCostHasOnlyTotalExecutions() {
        detailedExecution();
        execution(null, null);
        signInAs(UserRole.MEMBER);

        UsageDtos.MonthlyCostView cost = controller.thisMonthCost();

        assertThat(cost.totalExecutions()).as("가격을 찾은 실행 하나와 찾지 못한 실행 하나").isEqualTo(2L);
        assertThat(cost.month()).isNotBlank();
        assertThat(cost.currency()).as("currency").isNull();
        assertThat(cost.estimatedCostMicros()).as("estimatedCostMicros").isNull();
        assertThat(cost.actualCostMicros()).as("actualCostMicros").isNull();
        assertThat(cost.pricedExecutions()).as("pricedExecutions").isNull();
        assertThat(cost.unpricedExecutions()).as("unpricedExecutions").isNull();
        assertThat(cost.subscriptionExecutions()).as("subscriptionExecutions").isNull();
    }

    @Test
    @DisplayName("ADMIN 역할의 이번 달 합계에는 금액과 건수 구분이 그대로 있고 실행 건수가 더해진다")
    void adminMonthlyCostKeepsAmountsAndAddsTotalExecutions() {
        detailedExecution();
        execution(null, null);

        UsageDtos.MonthlyCostView cost = controller.thisMonthCost();

        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.estimatedCostMicros()).isEqualTo(5_000L);
        assertThat(cost.actualCostMicros()).isEqualTo(4_000L);
        assertThat(cost.pricedExecutions()).isEqualTo(1L);
        assertThat(cost.unpricedExecutions()).isEqualTo(1L);
        assertThat(cost.subscriptionExecutions()).isEqualTo(2L);
        assertThat(cost.totalExecutions()).isEqualTo(2L);
    }

    @Test
    @DisplayName("실행이 하나도 없는 달은 MEMBER 역할에게 실행 건수 0 으로 온다")
    void memberMonthlyCostOfEmptyMonthIsZeroExecutions() {
        signInAs(UserRole.MEMBER);

        UsageDtos.MonthlyCostView cost = controller.thisMonthCost();

        assertThat(cost.totalExecutions()).isZero();
        assertThat(cost.pricedExecutions()).isNull();
    }

    @Test
    @DisplayName("MEMBER 역할이 묶음 합계를 부르면 FORBIDDEN 이고 ADMIN 역할은 받는다")
    void memberBreakdownIsForbiddenAndAdminGetsRows() {
        detailedExecution();

        UsageDtos.BreakdownView forAdmin = controller.breakdown("model", null);
        signInAs(UserRole.MEMBER);

        assertThat(forAdmin.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.label()).isEqualTo("example-model-large"));
        assertThatThrownBy(() -> controller.breakdown("model", null))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("MEMBER 역할의 스킬 합계에는 에이전트 코드가 비고 ADMIN 역할에게는 그대로 실린다")
    void memberSkillUsageHasNoAgentCodeAndAdminKeepsIt() {
        skillUses.deleteAll();
        AgentExecution used = execution(null, null);
        skillUses.save(ExecutionSkillUse.of(
                used.id(), "shopping", SkillUseSource.MODEL, Instant.parse("2026-09-01T00:00:00Z")));

        List<UsageDtos.MySkillUsageView> forAdmin = controller.mySkillUsage();
        signInAs(UserRole.MEMBER);
        List<UsageDtos.MySkillUsageView> forMember = controller.mySkillUsage();

        assertThat(forAdmin).singleElement().satisfies(row -> {
            assertThat(row.agentCode()).isEqualTo("list-dad");
            assertThat(row.skillName()).isEqualTo("shopping");
        });
        assertThat(forMember).singleElement().satisfies(row -> {
            assertThat(row.agentCode()).as("agentCode").isNull();
            assertThat(row.agentName()).isEqualTo("목록 아빠");
            assertThat(row.skillName()).isEqualTo("shopping");
            assertThat(row.count()).isEqualTo(1L);
        });
    }

    /** 내부 값이 모두 채워진 실패 실행이다. 빼는 값과 남기는 값을 한 줄에서 견주려고 쓴다. */
    private AgentExecution detailedExecution() {
        Instant startedAt = Instant.now();
        return executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(agent.id())
                .profileName("dad")
                .provider("example-provider")
                .model("example-model-large")
                .reasoningEffort("high")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.FAILED)
                .errorCode("PROVIDER_BLOCKED")
                .tokens(100L, 40L, 20L, 120L)
                .timing(startedAt, startedAt.plusMillis(1_234L))
                .contextChars(3_000L)
                .contextOmittedItems(2)
                .runtimeFingerprint("fingerprint-1")
                .instructionsHash("hash-1")
                .cost(new ExecutionCost(5_000L, 4_000L, "USD", "2026-09"))
                .build());
    }

    @Test
    @DisplayName("자식을 가진 실행만 hasChildren 이 참이다")
    void onlyRunsWithChildrenHaveHasChildrenTrue() {
        AgentExecution parent = execution(null, null);
        execution(parent.id(), parent.id());
        AgentExecution alone = execution(null, null);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page)
                .filteredOn(view -> view.id().equals(parent.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::hasChildren)
                .isEqualTo(true);
        assertThat(page)
                .filteredOn(view -> view.id().equals(alone.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::hasChildren)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("요청한 effort 를 싣고 고르지 않은 실행은 null 이다")
    void carriesRequestedEffortAndUnchosenRunIsNull() {
        AgentExecution chosen = execution(null, null, "high");
        AgentExecution byDefault = execution(null, null);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page)
                .filteredOn(view -> view.id().equals(chosen.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::reasoningEffort)
                .isEqualTo("high");
        assertThat(page)
                .filteredOn(view -> view.id().equals(byDefault.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::reasoningEffort)
                .isNull();
    }

    @Test
    @DisplayName("에이전트 행이 없어도 두 줄이 나오고 없는 쪽의 에이전트 칸만 빈다")
    void twoRowsComeOutWithoutAgentRowAndOnlyMissingSideAgentColumnIsEmpty() {
        Agent gone = agents.save(Agent.of(
                "list-gone",
                "지운 아빠",
                "gone",
                "http://127.0.0.1:1/p/gone",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID,
                Instant.now()));
        AgentExecution kept = execution(null, null);
        AgentExecution orphaned = executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(gone.id())
                .profileName("gone")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        agents.deleteById(gone.id());

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page).hasSize(2);
        assertThat(page)
                .filteredOn(view -> view.id().equals(orphaned.id()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.agentCode()).isNull();
                    assertThat(view.agentName()).isNull();
                });
        assertThat(page)
                .filteredOn(view -> view.id().equals(kept.id()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.agentCode()).isEqualTo("list-dad");
                    assertThat(view.agentName()).isEqualTo("목록 아빠");
                });
    }

    /**
     * 목록 조회가 내는 질의 수다.
     *
     * <p>목록을 읽는 것 하나와 자식을 확인하는 것 하나, 대화 번호를 공개 식별자로 바꾸는 것 하나, 실행에서 쓴 스킬 이름을 읽는 것 하나,
     * 에이전트를 한 번에 읽는 것 하나다.
     */
    private static final long QUERIES_PER_LIST = 5;

    @Test
    @DisplayName("목록이 길어져도 자식을 확인하는 질의는 늘지 않는다")
    void childCheckQueriesDoNotGrowAsListGrows() {
        long few = queriesForList(2);
        long many = queriesForList(10);

        assertThat(few).as("실행 2개일 때 %d 번 질의했다", few).isEqualTo(QUERIES_PER_LIST);
        assertThat(many).as("실행 10개일 때 %d 번 질의했다. 2개일 때는 %d 번이었다", many, few).isEqualTo(QUERIES_PER_LIST);
    }

    /** 실행을 그만큼 넣고 목록을 한 번 부르면서 질의 수를 센다. */
    private long queriesForList(int howMany) {
        executions.deleteAll();
        for (int i = 0; i < howMany; i++) {
            execution(null, null);
        }
        Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<UsageDtos.ExecutionView> page = controller.myExecutions(200);

        assertThat(page).hasSize(howMany);
        return statistics.getQueryExecutionCount();
    }

    private AgentExecution execution(Long parentId, Long rootId) {
        return execution(parentId, rootId, null);
    }

    private AgentExecution execution(Long parentId, Long rootId, String reasoningEffort) {
        return executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(agent.id())
                .parentExecutionId(parentId)
                .rootExecutionId(rootId)
                .profileName("dad")
                .reasoningEffort(reasoningEffort)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
    }
}
