package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.application.ExecutionTree;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
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

    /** 가족이 사는 곳의 달력으로 달을 끊는다. 컨테이너의 {@code TZ} 가 바뀌어도 경계가 흔들리지 않는다. */
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
        MonthlyCost cost = executions.sumCostBetween(currentUser.require().id(), from, to);
        return new MonthlyCostView(
                month.toString(),
                "USD",
                cost.totalMicros(),
                cost.pricedExecutions(),
                cost.unpricedExecutions());
    }

    /**
     * 한 달치 환산 금액.
     *
     * @param month {@code 2026-09} 형태의 대상 달
     * @param unpricedExecutions 가격을 찾지 못해 합계에 들어가지 못한 실행 수
     */
    public record MonthlyCostView(
            String month,
            String currency,
            Long estimatedCostMicros,
            Long pricedExecutions,
            Long unpricedExecutions) {
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
            Long estimatedCostMicros,
            String costCurrency,
            String pricingVersion,
            Instant startedAt,
            boolean hasChildren) {

        /** 자식을 세지 않고 부르는 자리를 위한 것이다. 자식이 없는 것으로 본다. */
        static ExecutionView from(AgentExecution execution, Agent agent) {
            return from(execution, agent, false);
        }

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
                    execution.estimatedCostMicros(),
                    execution.costCurrency(),
                    execution.pricingVersion(),
                    execution.startedAt(),
                    hasChildren);
        }
    }
}
