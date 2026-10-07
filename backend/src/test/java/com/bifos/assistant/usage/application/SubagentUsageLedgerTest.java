package com.bifos.assistant.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient.SessionLookup;
import com.bifos.assistant.hermes.dto.SubagentSessionUsage;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.SamplePriceCatalog;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.SubagentUsageJob;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * native 자식의 원장 줄이 월 합계와 축별 합계에 한 번만 더해지고, 금액을 확인하지 못한 자식이 건수로
 * 드러나는지 본다. 근거는 ADR-062 에 있다.
 *
 * <p>재조회 빈이 실제 시계를 읽으므로 시각은 모두 검사를 시작한 순간에서 떨어진 거리로 적는다.
 */
@BackendIntegrationTest
@SamplePriceCatalog
class SubagentUsageLedgerTest {

    private static final Long USER_ID = 4_501L;
    private static final Long OTHER_USER_ID = 4_502L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final String AGENT_CODE = "ledger-dad";
    private static final String FINGERPRINT = "f1a9";
    private static final String CHILD = "ledger-child-1";

    /** 부모 실행의 provider 와 모델이다. 표본 가격표의 {@code openai/example-model} 에 닿는다. */
    private static final String PARENT_PROVIDER = "openai-codex";

    private static final String PARENT_MODEL = "example-model";
    private static final long PARENT_INPUT = 1_000L;
    private static final long PARENT_OUTPUT = 500L;

    /** 입력 1,000 x 5 + 출력 500 x 30. 단가는 100만 토큰당 USD 라 토큰당 micros 와 같은 수다. */
    private static final long PARENT_MICROS = 20_000L;

    /** 자식의 provider 와 모델이다. 표본 가격표의 {@code anthropic/example-model-large} 다. */
    private static final String CHILD_PROVIDER = "anthropic";

    private static final String CHILD_MODEL = "example-model-large";
    private static final long CHILD_PLAIN_INPUT = 200L;
    private static final long CHILD_CACHE_READ = 800L;
    private static final long CHILD_CACHE_WRITE = 0L;
    private static final long CHILD_INPUT = 1_000L;
    private static final long CHILD_OUTPUT = 500L;

    /** 일반 입력 200 x 15 + cache read 800 x 1.5 + 출력 500 x 75. */
    private static final long CHILD_MICROS = 41_700L;

    /** cache 구분 없이 입력 1,000 전부에 입력 단가 15 를 매겼을 때의 값이다. 이 값이 나오면 안 된다. */
    private static final long CHILD_MICROS_AT_FLAT_INPUT_RATE = 52_500L;

    /** {@code agent_delegate} 자식 실행의 금액이다. {@code openai/gpt-flat} 로 입력 1,000 x 2 + 출력 500 x 8. */
    private static final long DELEGATED_MICROS = 6_000L;

    @Autowired
    UsageSummaryService summaries;

    @Autowired
    SubagentUsageReconciler reconciler;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository events;

    @Autowired
    SubagentUsageJobRepository jobs;

    @Autowired
    AgentRepository agents;

    /** 자식 session 조회만 대역으로 바꾼다. 저장소와 재조회는 실제 빈이다. */
    @Autowired
    StubHermesRunsClient hermes;

    private Agent agent;
    private Instant now;
    private Instant from;
    private Instant to;

    @BeforeEach
    void startFromAnEmptyLedger() {
        jobs.deleteAll();
        events.deleteAll();
        executions.deleteAll();
        agent = agents.findByCode(AGENT_CODE)
                .orElseGet(() -> agents.save(Agent.of(
                        AGENT_CODE,
                        "원장 비서",
                        AGENT_CODE,
                        "http://127.0.0.1:1/p/" + AGENT_CODE,
                        CostMode.API,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID,
                        Instant.now())));
        // 저장소가 돌려주는 시각과 그대로 견줄 수 있게 저장 정밀도 안쪽으로 자른다.
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        from = now.minus(Duration.ofDays(10));
        to = now.plus(Duration.ofHours(1));
    }

    @Test
    @DisplayName("자식이 없으면 합계는 실행만이고 자식 건수는 모두 0 이다")
    void countsNoSubagentsWhenThereAreNoChildren() {
        finishedParent(USER_ID, justFinished());

        assertThat(summaries.monthly(USER_ID, from, to))
                .isEqualTo(new MonthlyUsageSummary(PARENT_MICROS, PARENT_MICROS, 1, 0, 0, 0, 0, 0, 0));
        assertThat(summaries.byAgent(USER_ID, from, to))
                .singleElement()
                .satisfies(line -> assertThat(line.subagents()).isZero());
    }

    @Test
    @DisplayName("부모와 다른 provider 와 모델로 돈 자식의 금액이 월 합계에 더해진다")
    void addsChildOnAnotherProviderToMonthlyTotal() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        recordChild(parent, CHILD, pricedUsage(CHILD));

        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);

        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
        assertThat(summary.actualMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
        assertThat(summary.pricedExecutions()).as("자식은 실행 수에 세지 않는다").isOne();
        assertThat(summary.pricedSubagents()).isOne();
        assertThat(summary.pendingSubagents()).isZero();
        assertThat(summary.unconfirmedSubagents()).isZero();
        assertThat(summary.unpricedSubagents()).isZero();
    }

    @Test
    @DisplayName("모델 축에는 자식의 provider 와 모델 줄이 실행 0건으로 따로 생긴다")
    void modelAxisGetsSeparateLineForChildModel() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        recordChild(parent, CHILD, pricedUsage(CHILD));

        List<BreakdownLine<CostByModel>> lines = summaries.byModel(USER_ID, from, to);

        assertThat(lines).as("금액이 큰 자식 줄이 앞에 온다").hasSize(2);
        assertThat(lines.get(0).subagents()).isOne();
        assertThat(lines.get(0).cost())
                .isEqualTo(new CostByModel(
                        CHILD_PROVIDER,
                        CHILD_MODEL,
                        0L,
                        CHILD_MICROS,
                        CHILD_MICROS,
                        CHILD_INPUT,
                        CHILD_OUTPUT,
                        null,
                        parent.startedAt(),
                        parent.startedAt()));
        assertThat(lines.get(1).subagents()).isZero();
        assertThat(lines.get(1).cost().provider()).isEqualTo(PARENT_PROVIDER);
        assertThat(lines.get(1).cost().model()).isEqualTo(PARENT_MODEL);
        assertThat(lines.get(1).cost().executions()).isOne();
        assertThat(lines.get(1).cost().estimatedMicros()).isEqualTo(PARENT_MICROS);
    }

    @Test
    @DisplayName("에이전트, 날짜, 지문 축은 부모 줄에 자식의 금액과 토큰을 더한다")
    void parentAxesAddChildAmountAndTokensToParentLine() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        recordChild(parent, CHILD, pricedUsage(CHILD));

        assertThat(summaries.byAgent(USER_ID, from, to)).singleElement().satisfies(line -> {
            CostByAgent cost = line.cost();
            assertThat(line.subagents()).isOne();
            assertThat(cost.agentId()).isEqualTo(agent.id());
            assertThat(cost.executions()).isOne();
            assertThat(cost.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
            assertThat(cost.actualMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
            assertThat(cost.inputTokens()).isEqualTo(PARENT_INPUT + CHILD_INPUT);
            assertThat(cost.outputTokens()).isEqualTo(PARENT_OUTPUT + CHILD_OUTPUT);
        });
        assertThat(summaries.byDay(USER_ID, from, to, ZONE)).singleElement().satisfies(line -> {
            CostByDay cost = line.cost();
            assertThat(line.subagents()).isOne();
            assertThat(cost.day()).isEqualTo(parent.startedAt().atZone(ZONE).toLocalDate());
            assertThat(cost.executions()).isOne();
            assertThat(cost.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
            assertThat(cost.inputTokens()).isEqualTo(PARENT_INPUT + CHILD_INPUT);
            assertThat(cost.outputTokens()).isEqualTo(PARENT_OUTPUT + CHILD_OUTPUT);
        });
        assertThat(summaries.byFingerprint(USER_ID, from, to)).singleElement().satisfies(line -> {
            CostByFingerprint cost = line.cost();
            assertThat(line.subagents()).isOne();
            assertThat(cost.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(cost.executions()).isOne();
            assertThat(cost.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
        });
    }

    @Test
    @DisplayName("자식의 cache read 는 cache read 단가로 환산된다")
    void pricesChildCacheReadAtCacheReadRate() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        SubagentUsageJob job = recordChild(parent, CHILD, pricedUsage(CHILD));

        assertThat(job.cacheReadTokens()).isEqualTo(CHILD_CACHE_READ);
        assertThat(job.estimatedCostMicros())
                .as("입력 전부에 입력 단가를 매긴 값이 아니다")
                .isEqualTo(CHILD_MICROS)
                .isNotEqualTo(CHILD_MICROS_AT_FLAT_INPUT_RATE);
    }

    @Test
    @DisplayName("아직 끝나지 않은 자식은 확인 중으로 세고, 끝난 것을 확인하면 합계에 더한다")
    void countsUnfinishedChildAsPendingUntilItEnds() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        startEvent(parent, 1, CHILD);
        reconciler.discover(now);
        SubagentUsageJob job = onlyJob();

        stubSession(CHILD, usage(CHILD, CHILD_PROVIDER, null));
        reconciler.poll(job.id());
        MonthlyUsageSummary first = summaries.monthly(USER_ID, from, to);

        assertThat(first.pendingSubagents()).isOne();
        assertThat(first.pricedSubagents()).isZero();
        assertThat(first.estimatedMicros()).isEqualTo(PARENT_MICROS);

        stubSession(CHILD, pricedUsage(CHILD));
        reconciler.poll(job.id());
        MonthlyUsageSummary second = summaries.monthly(USER_ID, from, to);

        assertThat(second.pricedSubagents()).isOne();
        assertThat(second.pendingSubagents()).isZero();
        assertThat(second.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
        assertAskedAgentFor(CHILD);
    }

    @Test
    @DisplayName("줄이 저장된 채 다시 찾아도 줄은 하나이고 합계에 한 번만 더해진다")
    void rediscoveryAfterRestartKeepsSingleLine() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        startEvent(parent, 1, CHILD);
        stubSession(CHILD, pricedUsage(CHILD));
        reconciler.discover(now);

        reconciler.discover(now);
        reconciler.poll(onlyJob().id());

        assertAskedAgentFor(CHILD);
        assertThat(jobs.findByExecutionIdIn(List.of(parent.id()))).hasSize(1);
        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);
        assertThat(summary.pricedSubagents()).isOne();
        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
    }

    @Test
    @DisplayName("끝난 줄을 다시 조회해도 합계가 그대로다")
    void repollingRecordedLineKeepsTotal() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        SubagentUsageJob job = recordChild(parent, CHILD, pricedUsage(CHILD));
        MonthlyUsageSummary before = summaries.monthly(USER_ID, from, to);

        reconciler.poll(job.id());

        assertThat(summaries.monthly(USER_ID, from, to)).isEqualTo(before);
        assertThat(before.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
    }

    @Test
    @DisplayName("시작 사건이 둘이고 완료 사건이 이미 와 있어도 줄 하나로 한 번만 더한다")
    void duplicatedEventsStillYieldSingleLine() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        startEvent(parent, 1, CHILD);
        startEvent(parent, 2, CHILD);
        events.save(ExecutionEvent.builder()
                .executionId(parent.id())
                .sequence(3)
                .eventType(ExecutionEventType.SUBAGENT_COMPLETED)
                .hermesSessionId(CHILD)
                .model(CHILD_MODEL)
                .inputTokens(CHILD_INPUT)
                .outputTokens(CHILD_OUTPUT)
                .occurredAt(parent.finishedAt())
                .build());
        stubSession(CHILD, pricedUsage(CHILD));

        assertThat(summaries.monthly(USER_ID, from, to).pendingSubagents())
                .as("줄이 생기기 전에도 같은 자식을 한 번만 센다")
                .isOne();

        reconciler.discover(now);
        reconciler.poll(onlyJob().id());

        assertAskedAgentFor(CHILD);
        assertThat(jobs.findByExecutionIdIn(List.of(parent.id()))).hasSize(1);
        assertThat(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(parent.id())))
                .filteredOn(event -> event.eventType() == ExecutionEventType.SUBAGENT_COMPLETED)
                .hasSize(1);
        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);
        assertThat(summary.pricedSubagents()).isOne();
        assertThat(summary.pendingSubagents()).isZero();
        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS + CHILD_MICROS);
    }

    @Test
    @DisplayName("provider 를 읽지 못한 자식은 가격 미확인으로 세고 토큰만 모델 축에 남긴다")
    void childWithoutProviderIsCountedAsUnpriced() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        SubagentUsageJob job = recordChild(parent, CHILD, usage(CHILD, null, 2.5));

        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);

        assertThat(job.provider()).isNull();
        assertThat(summary.unpricedSubagents()).isOne();
        assertThat(summary.pricedSubagents()).isZero();
        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS);
        assertThat(summaries.byModel(USER_ID, from, to))
                .filteredOn(line -> line.cost().provider() == null)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.subagents()).isOne();
                    assertThat(line.cost().model()).isEqualTo(CHILD_MODEL);
                    assertThat(line.cost().executions()).isZero();
                    assertThat(line.cost().inputTokens()).isEqualTo(CHILD_INPUT);
                    assertThat(line.cost().outputTokens()).isEqualTo(CHILD_OUTPUT);
                    assertThat(line.cost().estimatedMicros()).as("0 으로 채우지 않는다").isNull();
                    assertThat(line.cost().actualMicros()).isNull();
                });
    }

    @Test
    @DisplayName("기한이 지난 줄은 만료되고 미확인으로 센다")
    void expiredLineIsCountedAsUnconfirmed() {
        AgentExecution parent = finishedParent(USER_ID, finishedLongAgo());
        ExecutionEvent start = startEvent(parent, 1, CHILD);
        SubagentUsageJob job =
                jobs.save(SubagentUsageJob.create(parent, start, agent.apiBaseUrl(), parent.finishedAt()));

        reconciler.poll(job.id());

        assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo("EXPIRED");
        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);
        assertThat(summary.unconfirmedSubagents()).isOne();
        assertThat(summary.pendingSubagents()).isZero();
        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS);
    }

    @Test
    @DisplayName("줄 없이 부모가 끝난 지 24시간이 지난 자식은 미확인으로 센다")
    void childNeverScheduledWithinWindowIsUnconfirmed() {
        AgentExecution parent = finishedParent(USER_ID, finishedLongAgo());
        startEvent(parent, 1, CHILD);

        reconciler.discover(now);

        assertThat(jobs.findByExecutionIdIn(List.of(parent.id())))
                .as("재조회가 더는 찾지 않는다")
                .isEmpty();
        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);
        assertThat(summary.unconfirmedSubagents()).isOne();
        assertThat(summary.pendingSubagents()).isZero();
    }

    @Test
    @DisplayName("줄이 아직 없어도 부모가 방금 끝났으면 확인 중으로 센다")
    void childNotYetScheduledIsPending() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        startEvent(parent, 1, CHILD);

        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);

        assertThat(summary.pendingSubagents()).isOne();
        assertThat(summary.unconfirmedSubagents()).isZero();
    }

    @Test
    @DisplayName("session 없이 온 시작 사건은 미확인으로 센다")
    void sessionlessStartIsUnconfirmed() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        startEvent(parent, 1, null);

        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);

        assertThat(summary.unconfirmedSubagents()).isOne();
        assertThat(summary.pendingSubagents()).isZero();
    }

    @Test
    @DisplayName("부모가 도는 중이면 그 자식은 어느 건수에도 없다")
    void childrenOfRunningParentAreNotCounted() {
        AgentExecution running = executions.save(execution(USER_ID)
                .status(ExecutionStatus.RUNNING)
                .startedAt(now.minusSeconds(30))
                .build());
        startEvent(running, 1, CHILD);
        startEvent(running, 2, null);

        assertThat(summaries.monthly(USER_ID, from, to)).isEqualTo(new MonthlyUsageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("agent_delegate 자식은 자기 실행 줄로, native 자식은 원장 줄로 한 번씩 더해진다")
    void delegatedChildAndNativeChildAreEachAddedOnce() {
        AgentExecution parent = finishedParent(USER_ID, justFinished());
        executions.save(execution(USER_ID)
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.id())
                .delegationKey("ledger-delegation-1")
                .provider("openai")
                .model("gpt-flat")
                .status(ExecutionStatus.SUCCEEDED)
                .tokens(1_000L, null, 500L, 1_500L)
                .cost(new ExecutionCost(DELEGATED_MICROS, DELEGATED_MICROS, "USD", "models.dev@sample"))
                .timing(parent.startedAt().plusSeconds(1), parent.finishedAt())
                .build());
        recordChild(parent, CHILD, pricedUsage(CHILD));

        MonthlyUsageSummary summary = summaries.monthly(USER_ID, from, to);

        assertThat(summary.estimatedMicros()).isEqualTo(PARENT_MICROS + DELEGATED_MICROS + CHILD_MICROS);
        assertThat(summary.pricedExecutions()).isEqualTo(2L);
        assertThat(summary.pricedSubagents()).isOne();
    }

    @Test
    @DisplayName("다른 사용자의 자식은 내 합계와 건수에 없다")
    void othersChildrenAreNotInMyTotals() {
        finishedParent(USER_ID, justFinished());
        AgentExecution others = finishedParent(OTHER_USER_ID, justFinished());
        recordChild(others, CHILD, pricedUsage(CHILD));
        startEvent(others, 2, "ledger-child-2");
        startEvent(others, 3, null);

        assertThat(summaries.monthly(USER_ID, from, to))
                .isEqualTo(new MonthlyUsageSummary(PARENT_MICROS, PARENT_MICROS, 1, 0, 0, 0, 0, 0, 0));
        assertThat(summaries.byModel(USER_ID, from, to))
                .singleElement()
                .satisfies(line -> assertThat(line.subagents()).isZero());
        assertThat(summaries.monthly(OTHER_USER_ID, from, to).pricedSubagents())
                .as("그 자식은 주인의 합계에는 있다")
                .isOne();
    }

    /** 시작 사건을 남기고 재조회가 줄을 만들어 그 자식의 최종 사용량을 적게 한다. */
    private SubagentUsageJob recordChild(AgentExecution parent, String session, SubagentSessionUsage usage) {
        startEvent(parent, 1, session);
        stubSession(session, usage);
        reconciler.discover(now);
        SubagentUsageJob job = onlyJob();
        reconciler.poll(job.id());
        assertAskedAgentFor(session);
        return jobs.findById(job.id()).orElseThrow();
    }

    /**
     * 자식 session 조회가 모두 그 에이전트의 Hermes 주소와 profile 로, 그 session 을 물었는지 본다. 대역은 session 만 보고 답하므로
     * 주소와 profile 이 틀려도 답이 나온다. 그래서 받은 값을 따로 확인한다.
     */
    private void assertAskedAgentFor(String session) {
        assertThat(hermes.subagentUsageLookups())
                .as("자식 session 조회가 받은 주소와 profile, session")
                .isNotEmpty()
                .containsOnly(new SessionLookup(agent.apiBaseUrl(), agent.hermesProfile(), session));
    }

    private SubagentUsageJob onlyJob() {
        List<SubagentUsageJob> all = jobs.findAll();
        assertThat(all).as("재조회가 만든 원장 줄").hasSize(1);
        return all.getFirst();
    }

    private void stubSession(String session, SubagentSessionUsage usage) {
        hermes.willReportSubagentUsage(session, usage);
    }

    /** provider 를 읽었고 이미 끝난 자식의 session 응답이다. */
    private static SubagentSessionUsage pricedUsage(String session) {
        return usage(session, CHILD_PROVIDER, 2.5);
    }

    private static SubagentSessionUsage usage(String session, String provider, Double endedAt) {
        return new SubagentSessionUsage(
                session,
                "subagent",
                "ledger-parent",
                CHILD_MODEL,
                provider,
                1.0,
                endedAt,
                CHILD_PLAIN_INPUT,
                CHILD_OUTPUT,
                CHILD_CACHE_READ,
                CHILD_CACHE_WRITE);
    }

    private ExecutionEvent startEvent(AgentExecution parent, int sequence, String session) {
        return events.save(ExecutionEvent.builder()
                .executionId(parent.id())
                .sequence(sequence)
                .eventType(ExecutionEventType.SUBAGENT_STARTED)
                .subagentName("researcher")
                .hermesSessionId(session)
                .occurredAt(parent.startedAt())
                .build());
    }

    /** 재조회가 아직 찾는 구간 안에 끝난 부모의 종료 시각이다. */
    private Instant justFinished() {
        return now.minusSeconds(10);
    }

    /** 재조회가 찾는 24시간이 지나 끝난 부모의 종료 시각이다. */
    private Instant finishedLongAgo() {
        return now.minus(Duration.ofHours(25));
    }

    private AgentExecution finishedParent(Long userId, Instant finishedAt) {
        return executions.save(execution(userId)
                .provider(PARENT_PROVIDER)
                .model(PARENT_MODEL)
                .status(ExecutionStatus.SUCCEEDED)
                .tokens(PARENT_INPUT, null, PARENT_OUTPUT, PARENT_INPUT + PARENT_OUTPUT)
                .cost(new ExecutionCost(PARENT_MICROS, PARENT_MICROS, "USD", "models.dev@sample"))
                .runtimeFingerprint(FINGERPRINT)
                .timing(finishedAt.minusSeconds(5), finishedAt)
                .build());
    }

    private AgentExecution.Builder execution(Long userId) {
        return AgentExecution.builder()
                .userId(userId)
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .hermesSessionId("ledger-parent")
                .costMode(agent.costMode());
    }
}
