package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.domain.SubagentLedgerRow;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.infra.SubagentUsageJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 달치 실행 합계에 native 자식의 원장 줄을 더한다.
 *
 * <p>실행 합계는 데이터베이스가 낸다. 자식 줄은 한 달치를 한 번 읽어 여기서 월 합계와 네 축에 나눠 더한다.
 * 자식 줄은 실행 줄보다 훨씬 적고, 어느 자식을 세는지의 규칙이 질의 하나에 남는다. {@code agent_delegate} 로
 * 만든 자식은 자기 실행 줄로 이미 합계에 들어 있어 여기서 다시 더하지 않는다. 근거는 ADR-059 에 있다.
 */
@Service
@Transactional(readOnly = true)
public class UsageSummaryService {

    /** 재조회가 원장 줄이 없는 자식을 찾는 구간이다. {@code SubagentUsageReconciler.discover} 와 같다. */
    private static final Duration DISCOVERY_WINDOW = Duration.ofHours(24);

    private final AgentExecutionRepository executions;
    private final SubagentUsageJobRepository jobs;
    private final ExecutionEventRepository events;
    private final Clock clock;

    @Autowired
    public UsageSummaryService(
            AgentExecutionRepository executions, SubagentUsageJobRepository jobs, ExecutionEventRepository events) {
        this(executions, jobs, events, Clock.systemUTC());
    }

    UsageSummaryService(
            AgentExecutionRepository executions,
            SubagentUsageJobRepository jobs,
            ExecutionEventRepository events,
            Clock clock) {
        this.executions = executions;
        this.jobs = jobs;
        this.events = events;
        this.clock = clock;
    }

    /** 실행 합계에 금액이 있는 자식을 더하고, 금액을 확인하지 못한 자식을 까닭별로 센다. */
    public MonthlyUsageSummary monthly(Long userId, Instant from, Instant to) {
        MonthlyCostDetail cost = executions.sumCostDetailBetween(userId, from, to);
        long estimated = cost.estimatedMicros();
        long actual = cost.actualMicros();
        long priced = 0;
        long unpriced = 0;
        long pending = 0;
        long unconfirmed = 0;
        for (SubagentLedgerRow row : jobs.findLedgerRows(userId, from, to)) {
            if (row.priced()) {
                estimated += row.estimatedCostMicros();
                actual += row.actualCostMicros() == null ? 0L : row.actualCostMicros();
                priced++;
            } else if (row.recorded()) {
                unpriced++;
            } else if ("WAITING".equals(row.status())) {
                pending++;
            } else if ("EXPIRED".equals(row.status())) {
                unconfirmed++;
            }
        }
        Instant now = clock.instant();
        Instant discoverableSince = now.minus(DISCOVERY_WINDOW);
        // 끝난 시각의 위쪽 경계는 어느 부모도 닿지 않는 미래로 둔다.
        Instant beyondAnyFinish = (to.isAfter(now) ? to : now).plus(DISCOVERY_WINDOW);
        pending += events.countUnscheduledChildren(userId, from, to, discoverableSince, beyondAnyFinish);
        unconfirmed += events.countSessionlessChildren(userId, from, to);
        unconfirmed += events.countUnscheduledChildren(userId, from, to, Instant.EPOCH, discoverableSince);
        return new MonthlyUsageSummary(
                estimated,
                actual,
                cost.pricedExecutions(),
                cost.unpricedExecutions(),
                cost.subscriptionExecutions(),
                priced,
                pending,
                unconfirmed,
                unpriced);
    }

    /** 에이전트별 합계다. 자식은 부모 실행의 에이전트 줄에 더한다. */
    public List<BreakdownLine<CostByAgent>> byAgent(Long userId, Instant from, Instant to) {
        Map<Long, ChildSum> children = childSums(userId, from, to, SubagentLedgerRow::agentId);
        List<BreakdownLine<CostByAgent>> lines = merge(
                executions.sumByAgentBetween(userId, from, to),
                children,
                CostByAgent::agentId,
                (cost, child) -> new CostByAgent(
                        cost.agentId(),
                        cost.agentCode(),
                        cost.agentName(),
                        cost.executions(),
                        plus(cost.estimatedMicros(), child.estimatedMicros),
                        plus(cost.actualMicros(), child.actualMicros),
                        plus(cost.inputTokens(), child.inputTokens),
                        plus(cost.outputTokens(), child.outputTokens),
                        cost.avgContextChars(),
                        cost.firstSeenAt(),
                        cost.lastSeenAt()));
        // 자식 금액이 순서를 바꿀 수 있어 질의와 같은 기준으로 다시 정렬한다.
        lines.sort(Comparator.comparing(
                        (BreakdownLine<CostByAgent> line) -> orZero(line.cost().estimatedMicros()))
                .reversed()
                .thenComparing(line -> line.cost().agentId()));
        return lines;
    }

    /**
     * provider 와 모델별 합계다. 자식은 자기 provider 와 모델의 줄에 더한다.
     *
     * <p>부모와 다른 모델로 돈 자식은 실행 줄이 없어 새 줄이 된다. 그 줄의 실행 수는 0 이다. provider 를
     * 읽지 못한 자식은 provider 가 null 인 줄에 모인다.
     */
    public List<BreakdownLine<CostByModel>> byModel(Long userId, Instant from, Instant to) {
        Map<List<String>, ChildSum> children =
                childSums(userId, from, to, row -> Arrays.asList(row.provider(), row.model()));
        List<BreakdownLine<CostByModel>> lines = merge(
                executions.sumByModelBetween(userId, from, to),
                children,
                cost -> Arrays.asList(cost.provider(), cost.model()),
                (cost, child) -> new CostByModel(
                        cost.provider(),
                        cost.model(),
                        cost.executions(),
                        plus(cost.estimatedMicros(), child.estimatedMicros),
                        plus(cost.actualMicros(), child.actualMicros),
                        plus(cost.inputTokens(), child.inputTokens),
                        plus(cost.outputTokens(), child.outputTokens),
                        cost.avgContextChars(),
                        cost.firstSeenAt(),
                        cost.lastSeenAt()));
        // merge 가 붙인 줄을 뺀 나머지가 실행 줄이 없는 모델이다.
        children.forEach((key, child) -> lines.add(new BreakdownLine<>(
                new CostByModel(
                        key.get(0),
                        key.get(1),
                        0L,
                        child.estimatedMicros,
                        child.actualMicros,
                        child.inputTokens,
                        child.outputTokens,
                        null,
                        child.firstSeenAt,
                        child.lastSeenAt),
                child.subagents)));
        lines.sort(Comparator.comparing(
                        (BreakdownLine<CostByModel> line) -> orZero(line.cost().estimatedMicros()))
                .reversed()
                .thenComparing(line -> line.cost().model(), Comparator.nullsFirst(Comparator.naturalOrder())));
        return lines;
    }

    /** 날짜별 합계다. 자식은 부모 실행이 시작한 날의 줄에 더한다. 날짜는 {@code zone} 의 달력으로 끊는다. */
    public List<BreakdownLine<CostByDay>> byDay(Long userId, Instant from, Instant to, ZoneId zone) {
        Map<LocalDate, ChildSum> children = childSums(
                userId, from, to, row -> row.parentStartedAt().atZone(zone).toLocalDate());
        return merge(
                executions.sumByDayBetween(userId, from, to),
                children,
                CostByDay::day,
                (cost, child) -> new CostByDay(
                        cost.day(),
                        cost.executions(),
                        plus(cost.estimatedMicros(), child.estimatedMicros),
                        plus(cost.actualMicros(), child.actualMicros),
                        plus(cost.inputTokens(), child.inputTokens),
                        plus(cost.outputTokens(), child.outputTokens),
                        cost.avgContextChars(),
                        cost.firstSeenAt(),
                        cost.lastSeenAt()));
    }

    /** 문맥 지문별 합계다. 자식은 부모 실행의 지문 줄에 더한다. 부모에 지문이 없으면 이 축에서 빠진다. */
    public List<BreakdownLine<CostByFingerprint>> byFingerprint(Long userId, Instant from, Instant to) {
        Map<String, ChildSum> children = childSums(userId, from, to, SubagentLedgerRow::runtimeFingerprint);
        return merge(
                executions.sumByFingerprintBetween(userId, from, to),
                children,
                CostByFingerprint::fingerprint,
                (cost, child) -> new CostByFingerprint(
                        cost.fingerprint(),
                        cost.executions(),
                        plus(cost.estimatedMicros(), child.estimatedMicros),
                        plus(cost.actualMicros(), child.actualMicros),
                        plus(cost.inputTokens(), child.inputTokens),
                        plus(cost.outputTokens(), child.outputTokens),
                        cost.avgContextChars(),
                        cost.firstSeenAt(),
                        cost.lastSeenAt()));
    }

    /** 사용량을 적은 자식 줄을 축의 값으로 묶어 더한다. 축 값이 없는 줄은 건너뛴다. */
    private <K> Map<K, ChildSum> childSums(
            Long userId, Instant from, Instant to, Function<SubagentLedgerRow, K> keyOf) {
        Map<K, ChildSum> sums = new LinkedHashMap<>();
        for (SubagentLedgerRow row : jobs.findLedgerRows(userId, from, to)) {
            K key = row.recorded() ? keyOf.apply(row) : null;
            if (key != null) {
                sums.computeIfAbsent(key, ignored -> new ChildSum()).add(row);
            }
        }
        return sums;
    }

    /**
     * 실행 합계의 줄마다 같은 축 값의 자식 합을 더한다.
     *
     * <p>더한 자식 합은 {@code children} 에서 뺀다. 남은 것은 실행 줄이 없는 축 값이다.
     */
    private static <T, K> List<BreakdownLine<T>> merge(
            List<T> costs, Map<K, ChildSum> children, Function<T, K> keyOf, BiFunction<T, ChildSum, T> add) {
        List<BreakdownLine<T>> lines = new ArrayList<>();
        for (T cost : costs) {
            ChildSum child = children.remove(keyOf.apply(cost));
            lines.add(
                    child == null
                            ? new BreakdownLine<>(cost, 0L)
                            : new BreakdownLine<>(add.apply(cost, child), child.subagents));
        }
        return lines;
    }

    /** 둘 다 없으면 null 로 둔다. 0 은 공짜라는 뜻으로 읽힌다. */
    private static Long plus(Long base, Long added) {
        if (base == null && added == null) {
            return null;
        }
        return orZero(base) + orZero(added);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 축 값 하나에 모인 자식들의 합이다. 하나도 더하지 않은 칸은 null 로 남는다. */
    private static final class ChildSum {
        private long subagents;
        private Long estimatedMicros;
        private Long actualMicros;
        private Long inputTokens;
        private Long outputTokens;
        private Instant firstSeenAt;
        private Instant lastSeenAt;

        private void add(SubagentLedgerRow row) {
            subagents++;
            estimatedMicros = plus(estimatedMicros, row.estimatedCostMicros());
            actualMicros = plus(actualMicros, row.actualCostMicros());
            inputTokens = plus(inputTokens, row.inclusiveInputTokens());
            outputTokens = plus(outputTokens, row.outputTokens());
            Instant startedAt = row.parentStartedAt();
            firstSeenAt = firstSeenAt == null || startedAt.isBefore(firstSeenAt) ? startedAt : firstSeenAt;
            lastSeenAt = lastSeenAt == null || startedAt.isAfter(lastSeenAt) ? startedAt : lastSeenAt;
        }
    }
}
