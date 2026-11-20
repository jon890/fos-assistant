package com.bifos.assistant.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.admin.usage.application.AdminOverviewService;
import com.bifos.assistant.admin.usage.application.dto.OverviewFailures;
import com.bifos.assistant.admin.usage.application.dto.OverviewMonthly;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.pricing.domain.ExecutionCost;
import com.bifos.assistant.usage.summary.application.UsageSummaryService;
import com.bifos.assistant.usage.summary.application.dto.MonthlyUsageSummary;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 관리자 요약의 두 카드가 그룹 전체를 같은 규칙으로 세는지 본다. 검사마다 새 그룹을 만들어 다른 검사의 실행과 섞지 않는다. */
@BackendIntegrationTest
class AdminOverviewTest {

    /** 서울 2026-11-20 12:00. 오늘을 포함한 7일은 서울 2026-11-14 0시(UTC 11-13 15:00)부터다. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final Instant SINCE = Instant.parse("2026-11-13T15:00:00Z");

    @Autowired
    AdminOverviewService overview;

    @Autowired
    UsageSummaryService summaries;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    TestClock clock;

    private long groupId;

    private Agent agent;


    @BeforeEach
    void freshGroup() {
        clock.set(NOW);
        groupId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
    }


    @Test
    @DisplayName("실행 기록과 같은 실패 규칙으로 루트 실행만 센다")
    void countsFailuresWithExecutionListRule() {
        CurrentUser admin = user("관리자", UserRole.ADMIN);
        save(admin, ExecutionStatus.FAILED, null, NOW.minusSeconds(60), null);
        save(admin, ExecutionStatus.SUCCEEDED, "TOOL_ERROR", NOW.minusSeconds(120), null);
        save(admin, ExecutionStatus.CANCELLED, null, NOW.minusSeconds(180), null);
        save(admin, ExecutionStatus.RUNNING, "HERMES_BUSY", NOW.minusSeconds(240), null);
        AgentExecution root = save(admin, ExecutionStatus.SUCCEEDED, null, NOW.minusSeconds(300), null);
        save(admin, ExecutionStatus.FAILED, "CHILD_FAILED", NOW.minusSeconds(290), root.id());

        OverviewFailures failures = overview.failures(admin);

        assertThat(failures.total()).isEqualTo(2);
        assertThat(failures.recent()).extracting(OverviewFailures.Line::errorCode).containsExactly(null, "TOOL_ERROR");
        assertThat(failures.recent()).allSatisfy(line -> assertThat(line.agentName()).isEqualTo("요약 비서"));
    }


    @Test
    @DisplayName("서울 날짜로 오늘을 포함한 7일 안의 실패만 센다")
    void countsOnlyFailuresSinceSeoulMidnightSixDaysAgo() {
        CurrentUser admin = user("관리자", UserRole.ADMIN);
        save(admin, ExecutionStatus.FAILED, "IN", SINCE, null);
        save(admin, ExecutionStatus.FAILED, "OUT", SINCE.minusSeconds(1), null);

        OverviewFailures failures = overview.failures(admin);

        assertThat(failures.since()).isEqualTo(SINCE);
        assertThat(failures.total()).isEqualTo(1);
        assertThat(failures.recent()).extracting(OverviewFailures.Line::errorCode).containsExactly("IN");
    }


    @Test
    @DisplayName("같은 그룹의 남의 실패도 이름과 함께 세고 내 실행만 mine 이며 다른 그룹은 세지 않는다")
    void countsWholeGroupAndMarksOnlyMine() {
        CurrentUser admin = user("관리자", UserRole.ADMIN);
        CurrentUser kid = user("아이", UserRole.MEMBER);
        CurrentUser stranger = new CurrentUser(
            users.save(AppUser.of(email(), "남", groupId + 1, UserRole.ADMIN)).id(), "x", "남", groupId + 1, UserRole.ADMIN);
        save(admin, ExecutionStatus.FAILED, "A1", NOW.minusSeconds(500), null);
        save(admin, ExecutionStatus.FAILED, "A2", NOW.minusSeconds(400), null);
        save(kid, ExecutionStatus.FAILED, "K1", NOW.minusSeconds(300), null);
        save(kid, ExecutionStatus.FAILED, "K2", NOW.minusSeconds(200), null);
        save(kid, ExecutionStatus.FAILED, "K3", NOW.minusSeconds(100), null);
        save(stranger, ExecutionStatus.FAILED, "S1", NOW.minusSeconds(50), null);

        OverviewFailures failures = overview.failures(admin);

        assertThat(failures.total()).isEqualTo(5);
        assertThat(failures.recent()).extracting(OverviewFailures.Line::errorCode).containsExactly("K3", "K2", "K1");
        assertThat(failures.recent()).allSatisfy(line -> {
            assertThat(line.userName()).isEqualTo("아이");
            assertThat(line.mine()).isFalse();
        });
        assertThat(overview.failures(kid).recent()).allSatisfy(line -> assertThat(line.mine()).isTrue());
    }


    @Test
    @DisplayName("이번 달 합계는 그룹 사용자마다의 월 합계를 더한 것이고 한 사람이면 그 사람의 월 합계와 같다")
    void monthlyIsSumOfMembers() {
        CurrentUser admin = user("관리자", UserRole.ADMIN);
        saveCost(admin, NOW.minusSeconds(60), 10_000L, 2_000L);
        saveCost(admin, NOW.minusSeconds(120), 5_000L, null);

        OverviewMonthly alone = overview.monthly(admin);
        MonthlyUsageSummary own = summaries.monthly(
            admin.id(), Instant.parse("2026-10-31T15:00:00Z"), Instant.parse("2026-11-30T15:00:00Z"));
        assertThat(alone.month()).isEqualTo("2026-11");
        assertThat(alone.totalExecutions()).isEqualTo(own.pricedExecutions() + own.unpricedExecutions());
        assertThat(alone.subscriptionExecutions()).isEqualTo(own.subscriptionExecutions());
        assertThat(alone.actualCostMicros()).isEqualTo(own.actualMicros());
        assertThat(alone.incomplete()).isFalse();

        CurrentUser kid = user("아이", UserRole.MEMBER);
        saveCost(kid, NOW.minusSeconds(30), 1_000L, 500L);
        OverviewMonthly group = overview.monthly(admin);
        assertThat(group.totalExecutions()).isEqualTo(3);
        assertThat(group.actualCostMicros()).isEqualTo(2_500L);
    }


    @Test
    @DisplayName("가격을 찾지 못한 실행이 있으면 금액이 덜 셌다고 알린다")
    void flagsIncompleteWhenAnExecutionHasNoPrice() {
        CurrentUser admin = user("관리자", UserRole.ADMIN);
        saveCost(admin, NOW.minusSeconds(60), null, null);

        assertThat(overview.monthly(admin).incomplete()).isTrue();
    }


    private CurrentUser user(String name, UserRole role) {
        AppUser saved = users.save(AppUser.of(email(), name, groupId, role));
        if (agent == null) {
            String code = "overview-" + UUID.randomUUID().toString().substring(0, 8);
            agent = agents.save(Agent.of(
                code, "요약 비서", code, "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, saved.id()));
        }
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }


    private static String email() {
        return "overview-" + UUID.randomUUID() + "@example.com";
    }


    private AgentExecution save(CurrentUser owner, ExecutionStatus status, String errorCode, Instant started, Long rootId) {
        return executions.save(base(owner, started)
            .status(status)
            .errorCode(errorCode)
            .rootExecutionId(rootId)
            .parentExecutionId(rootId)
            .build());
    }


    private void saveCost(CurrentUser owner, Instant started, Long estimatedMicros, Long actualMicros) {
        executions.save(base(owner, started)
            .status(ExecutionStatus.SUCCEEDED)
            .cost(new ExecutionCost(estimatedMicros, actualMicros, "USD", "test"))
            .build());
    }


    private AgentExecution.Builder base(CurrentUser owner, Instant started) {
        return AgentExecution.builder()
            .userId(owner.id())
            .conversationId(1L)
            .agentId(agent.id())
            .profileName(agent.hermesProfile())
            .provider("openai-codex")
            .model("example-model")
            .costMode(CostMode.SUBSCRIPTION)
            .timing(started, started.plusMillis(1_000L));
    }
}
