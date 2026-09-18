package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionTree;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.presentation.UsageDtos.BreakdownRow;
import com.bifos.assistant.usage.presentation.UsageDtos.BreakdownView;
import com.bifos.assistant.usage.presentation.UsageDtos.ExecutionView;
import com.bifos.assistant.usage.presentation.UsageDtos.MonthlyCostView;
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
}
