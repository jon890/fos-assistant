package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import java.time.Instant;
import java.util.List;

/**
 * 사용량 화면이 받는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 화면에 나가는 모양은 여기 둔다. 같은 저장소의 {@code ChatDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
public final class UsageDtos {

    private UsageDtos() {
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
            Integer contextOmittedItems,
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
                    execution.contextOmittedItems(),
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
