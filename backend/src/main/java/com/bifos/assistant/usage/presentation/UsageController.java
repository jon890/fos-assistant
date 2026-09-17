package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** 로그인한 사용자 자신의 실행만 준다. 구성원을 가로질러 보는 것은 admin 화면이 맡는다. */
    @GetMapping("/executions")
    public List<ExecutionView> myExecutions(@RequestParam(defaultValue = "50") int limit) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        return executions
                .findByUserIdOrderByIdDesc(currentUser.require().id(), PageRequest.of(0, size))
                .stream()
                .map(execution -> ExecutionView.from(execution, agents.requireById(execution.agentId())))
                .toList();
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
     * 한 달치 환산액과 실제 청구액.
     *
     * @param month {@code 2026-09} 형태의 대상 달
     * @param unpricedExecutions 가격을 찾지 못해 합계에 들어가지 못한 실행 수
     * @param actualCostMicros 실제 청구액의 합. 구독 경로에서는 종량 경로였다면 낼 금액이 청구되지 않는다
     * @param subscriptionExecutions 실제 청구액이 비어 있는 실행 수
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
            Long actualCostMicros,
            String costCurrency,
            String pricingVersion,
            Instant startedAt) {

        static ExecutionView from(AgentExecution execution, Agent agent) {
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
                    execution.actualCostMicros(),
                    execution.costCurrency(),
                    execution.pricingVersion(),
                    execution.startedAt());
        }
    }
}
