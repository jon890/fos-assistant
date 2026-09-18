package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionTree;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private static final int MAX_LIMIT = 200;

    /**
     * 가족이 사는 곳의 달력으로 달을 끊는다. 컨테이너의 {@code TZ} 가 바뀌어도 경계가 흔들리지 않는다.
     *
     * <p>같은 시간대를 {@code AgentExecutionRepository.sumByDayBetween} 의 질의도 갖는다. 그 질의는
     * 표준시와의 차이 9시간을 식에 직접 적어 날짜를 뽑는다. 가족이 사는 곳이 바뀌면 두 자리를 함께
     * 고친다.
     */
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    private final AgentExecutionRepository executions;
    private final CurrentUserProvider currentUser;
    private final AgentService agents;
    private final ExecutionTreeService executionTrees;

    /** 로그인한 사용자 자신의 실행만 준다. 구성원을 가로질러 보는 것은 admin 화면이 맡는다. */
    @GetMapping("/executions")
    public List<ExecutionView> myExecutions(@RequestParam(defaultValue = "50") int limit) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        List<AgentExecution> page =
                executions.findByUserIdOrderByIdDesc(currentUser.require().id(), PageRequest.of(0, size));
        Set<Long> withChildren = idsHavingChildren(page);
        return page.stream()
                .map(execution ->
                        ExecutionView.from(
                                execution,
                                agents.requireById(execution.agentId()),
                                withChildren.contains(execution.id())))
                .toList();
    }

    /**
     * 목록의 실행 중 자식을 가진 것을 한 번에 읽는다.
     *
     * <p>실행마다 세면 질의가 목록 길이만큼 늘어난다. 목록이 비면 부르지 않는다. 빈 {@code in} 절은
     * 데이터베이스마다 다르게 동작한다.
     */
    private Set<Long> idsHavingChildren(List<AgentExecution> page) {
        if (page.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                executions.findParentIdsHavingChildren(page.stream().map(AgentExecution::id).toList()));
    }

    /**
     * 그 실행이 속한 나무를 낸다.
     *
     * <p>자식 실행의 번호로 물어도 뿌리부터 낸다. 없는 실행과 남의 실행은 같은 응답으로 숨긴다.
     */
    @GetMapping("/executions/{id}/tree")
    public ExecutionTree executionTree(@PathVariable Long id) {
        return executionTrees.of(currentUser.require(), id);
    }

    /**
     * 이번 달 환산 금액의 합계다.
     *
     * <p>구독료와 견줄 숫자라서 화면이 목록과 함께 보여 준다. 저장된 금액을 더하기만 하고 여기서 다시
     * 환산하지 않는다.
     */
    @GetMapping("/monthly-cost")
    public MonthlyCostView thisMonthCost() {
        YearMonth month = YearMonth.now(HOUSEHOLD_ZONE);
        Instant from = month.atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        MonthlyCostDetail cost = executions.sumCostDetailBetween(currentUser.require().id(), from, to);
        return new MonthlyCostView(
                month.toString(),
                "USD",
                cost.estimatedMicros(),
                cost.pricedExecutions(),
                cost.unpricedExecutions(),
                cost.actualMicros(),
                cost.subscriptionExecutions());
    }

    /**
     * 한 달치 실행을 축 하나로 묶어 준다.
     *
     * <p>네 축이 같은 줄 모양을 쓴다. 화면이 표 하나로 네 축을 모두 그릴 수 있게 하려는 것이다.
     * 합계는 데이터베이스가 내고 축 하나에 질의 하나만 나간다.
     *
     * <p>자기 것만 낸다. 구성원을 가로질러 보는 것은 admin 화면이 맡는다.
     *
     * @param axis {@code agent}, {@code model}, {@code day}, {@code fingerprint} 중 하나
     * @param month {@code 2026-09} 형태의 대상 달. 없으면 이번 달
     */
    @GetMapping("/breakdown")
    public BreakdownView breakdown(
            @RequestParam String axis, @RequestParam(required = false) String month) {
        YearMonth target = parseMonth(month);
        Instant from = target.atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Instant to = target.plusMonths(1).atDay(1).atStartOfDay(HOUSEHOLD_ZONE).toInstant();
        Long userId = currentUser.require().id();
        return new BreakdownView(axis, target.toString(), "USD", rows(axis, userId, from, to));
    }

    private List<BreakdownRow> rows(String axis, Long userId, Instant from, Instant to) {
        return switch (axis) {
            case "agent" -> executions.sumByAgentBetween(userId, from, to).stream()
                    .map(BreakdownRow::of)
                    .toList();
            case "model" -> executions.sumByModelBetween(userId, from, to).stream()
                    .map(BreakdownRow::of)
                    .toList();
            case "day" -> executions.sumByDayBetween(userId, from, to).stream()
                    .map(BreakdownRow::of)
                    .toList();
            case "fingerprint" -> executions.sumByFingerprintBetween(userId, from, to).stream()
                    .map(BreakdownRow::of)
                    .toList();
            default -> throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "axis must be one of agent, model, day, fingerprint");
        };
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now(HOUSEHOLD_ZONE);
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "month must look like 2026-09", ex);
        }
    }

    /**
     * 한 달치 환산액과 실제 청구액.
     *
     * @param month {@code 2026-09} 형태의 대상 달
     * @param unpricedExecutions 가격을 찾지 못해 합계에 들어가지 못한 실행 수
     * @param actualCostMicros 실제 청구액의 합. 구독 경로에서는 종량 경로였다면 낼 금액이 청구되지 않는다
     * @param subscriptionExecutions 구독 경로로 돈 실행 수
     */
    public record MonthlyCostView(
            String month,
            String currency,
            Long estimatedCostMicros,
            Long pricedExecutions,
            Long unpricedExecutions,
            Long actualCostMicros,
            Long subscriptionExecutions) {
    }

    /**
     * 축 하나로 묶어 본 한 달치다.
     *
     * @param axis 어느 축으로 묶었는지
     * @param rows 묶음 줄. 축마다 정렬 기준이 다르고 그 순서대로 준다
     */
    public record BreakdownView(String axis, String month, String currency, List<BreakdownRow> rows) {
    }

    /**
     * 묶음 한 줄이다. 네 축이 같은 모양을 쓴다.
     *
     * @param key 그 묶음을 가리키는 값. 화면이 줄을 구분할 때 쓴다
     * @param label 사람이 읽을 이름
     * @param detail 이름 아래 작게 붙는 보조 설명. 없으면 null
     * @param avgContextChars 문맥 글자 수 평균. 남긴 실행이 하나도 없으면 null
     */
    public record BreakdownRow(
            String key,
            String label,
            String detail,
            Long executions,
            Long estimatedCostMicros,
            Long actualCostMicros,
            Long inputTokens,
            Long outputTokens,
            Long avgContextChars,
            Instant firstSeenAt,
            Instant lastSeenAt) {

        static BreakdownRow of(CostByAgent cost) {
            String code = cost.agentCode() == null ? String.valueOf(cost.agentId()) : cost.agentCode();
            return new BreakdownRow(
                    code,
                    cost.agentName() == null ? code : cost.agentName(),
                    cost.agentCode(),
                    cost.executions(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow of(CostByModel cost) {
            String model = cost.model() == null ? "모델 없음" : cost.model();
            return new BreakdownRow(
                    cost.provider() + "/" + model,
                    model,
                    cost.provider(),
                    cost.executions(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow of(CostByDay cost) {
            String day = cost.day().toString();
            return new BreakdownRow(
                    day,
                    day,
                    null,
                    cost.executions(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow of(CostByFingerprint cost) {
            return new BreakdownRow(
                    cost.fingerprint(),
                    cost.fingerprint(),
                    null,
                    cost.executions(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        private static Long round(Double average) {
            return average == null ? null : Math.round(average);
        }
    }

    /**
     * 사용량 목록의 한 줄.
     *
     * @param hasChildren 이 실행이 부른 실행이 있는가. 화면이 나무로 들어갈 곳을 고를 때 쓴다
     */
    public record ExecutionView(
            Long id,
            Long conversationId,
            String agentCode,
            String agentName,
            String provider,
            String model,
            String costMode,
            String status,
            String errorCode,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            Long latencyMs,
            Long contextChars,
            String runtimeFingerprint,
            String instructionsHash,
            Long estimatedCostMicros,
            Long actualCostMicros,
            String costCurrency,
            String pricingVersion,
            Instant startedAt,
            boolean hasChildren) {

        /**
         * 자식 여부를 받아서만 만든다.
         *
         * <p>{@code hasChildren} 을 거짓으로 채워 주는 짧은 형태를 두지 않는다. 그것을 부르면 자식이
         * 있는 실행이 목록에 표시 없이 보이고, 컴파일은 통과한다. 부르는 쪽이 자식을 셀지 정하게 한다.
         */
        static ExecutionView from(AgentExecution execution, Agent agent, boolean hasChildren) {
            return new ExecutionView(
                    execution.id(),
                    execution.conversationId(),
                    agent.code(),
                    agent.name(),
                    execution.provider(),
                    execution.model(),
                    execution.costMode().name(),
                    execution.status().name(),
                    execution.errorCode(),
                    execution.inputTokens(),
                    execution.cachedInputTokens(),
                    execution.outputTokens(),
                    execution.totalTokens(),
                    execution.latencyMs(),
                    execution.contextChars(),
                    execution.runtimeFingerprint(),
                    execution.instructionsHash(),
                    execution.estimatedCostMicros(),
                    execution.actualCostMicros(),
                    execution.costCurrency(),
                    execution.pricingVersion(),
                    execution.startedAt(),
                    hasChildren);
        }
    }
}
