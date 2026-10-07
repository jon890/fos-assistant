package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.skill.application.UserSkillUsage;
import com.bifos.assistant.usage.application.BreakdownLine;
import com.bifos.assistant.usage.application.MonthlyUsageSummary;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 사용량 화면이 받는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 화면에 나가는 모양은 여기 둔다. 같은 저장소의 {@code ChatDtos} 와
 * {@code MemoryDtos} 가 같은 규칙을 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UsageDtos {

    /**
     * 한 달치 환산액과 실제 청구액.
     *
     * <p>두 금액은 native 자식의 금액을 포함한다. 자식은 실행 수에 세지 않고 아래 네 칸으로 따로 센다.
     * {@code pendingSubagents}, {@code unconfirmedSubagents}, {@code unpricedSubagents} 가운데 하나라도 0 이
     * 아니면 합계가 실제보다 작다(ADR-062).
     *
     * @param month {@code 2026-09} 형태의 대상 달
     * @param estimatedCostMicros 환산 금액의 합. 금액이 있는 native 자식을 포함한다
     * @param unpricedExecutions 가격을 찾지 못해 합계에 들어가지 못한 실행 수
     * @param actualCostMicros 실제 청구액의 합. 구독 경로에서는 종량 경로였다면 낼 금액이 청구되지 않는다.
     *     금액이 있는 native 자식을 포함한다
     * @param subscriptionExecutions 구독 경로로 돈 실행 수
     * @param pricedSubagents 금액이 합계에 든 native 자식 수
     * @param pendingSubagents 사용량을 아직 확인하는 중인 native 자식 수
     * @param unconfirmedSubagents 사용량을 끝내 확인하지 못한 native 자식 수
     * @param unpricedSubagents 사용량은 적었지만 금액을 내지 못한 native 자식 수
     * @param totalExecutions 그 달 실행 건수. 가격을 찾은 것과 찾지 못한 것의 합이고 모두에게 싣는다
     */
    public record MonthlyCostView(
            String month,
            String currency,
            Long estimatedCostMicros,
            Long pricedExecutions,
            Long unpricedExecutions,
            Long actualCostMicros,
            Long subscriptionExecutions,
            Long pricedSubagents,
            Long pendingSubagents,
            Long unconfirmedSubagents,
            Long unpricedSubagents,
            Long observationIncompleteExecutions,
            long totalExecutions) {

        /**
         * 한 달치 합계를 보는 사람에 맞춰 옮긴다.
         *
         * <p>{@code internal} 이 거짓이면 통화와 금액과 건수 구분, native 자식 수를 비우고 달과 실행 건수만 싣는다.
         */
        static MonthlyCostView from(String month, MonthlyUsageSummary cost, boolean internal) {
            long total = cost.pricedExecutions() + cost.unpricedExecutions();
            if (!internal) {
                return new MonthlyCostView(month, null, null, null, null, null, null, null, null, null, null, null, total);
            }
            return new MonthlyCostView(
                    month,
                    "USD",
                    cost.estimatedMicros(),
                    cost.pricedExecutions(),
                    cost.unpricedExecutions(),
                    cost.actualMicros(),
                    cost.subscriptionExecutions(),
                    cost.pricedSubagents(),
                    cost.pendingSubagents(),
                    cost.unconfirmedSubagents(),
                    cost.unpricedSubagents(),
                    cost.observationIncompleteExecutions(),
                    total);
        }
    }

    /**
     * 축 하나로 묶어 본 한 달치다.
     *
     * @param axis 어느 축으로 묶었는지
     * @param rows 묶음 줄. 축마다 정렬 기준이 다르고 그 순서대로 준다
     */
    public record BreakdownView(String axis, String month, String currency, List<BreakdownRow> rows) {}

    /**
     * 묶음 한 줄이다. 네 축이 같은 모양을 쓴다.
     *
     * @param key 그 묶음을 가리키는 값. 화면이 줄을 구분할 때 쓴다
     * @param label 사람이 읽을 이름
     * @param detail 이름 아래 작게 붙는 보조 설명. 없으면 null
     * @param executions 그 묶음의 실행 수. native 자식은 세지 않는다
     * @param subagents 그 묶음의 금액과 토큰에 더한 native 자식 수
     * @param avgContextChars 문맥 글자 수 평균. 남긴 실행이 하나도 없으면 null
     */
    public record BreakdownRow(
            String key,
            String label,
            String detail,
            Long executions,
            Long subagents,
            Long estimatedCostMicros,
            Long actualCostMicros,
            Long inputTokens,
            Long outputTokens,
            Long avgContextChars,
            Instant firstSeenAt,
            Instant lastSeenAt) {

        static BreakdownRow ofAgent(BreakdownLine<CostByAgent> line) {
            CostByAgent cost = line.cost();
            String code = cost.agentId() == null
                    ? "system-decision"
                    : cost.agentCode() == null ? String.valueOf(cost.agentId()) : cost.agentCode();
            String label = cost.agentId() == null ? "시스템 판단" : cost.agentName() == null ? code : cost.agentName();
            return new BreakdownRow(
                    code,
                    label,
                    cost.agentCode(),
                    cost.executions(),
                    line.subagents(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow ofModel(BreakdownLine<CostByModel> line) {
            CostByModel cost = line.cost();
            String model = cost.model() == null ? "모델 없음" : cost.model();
            return new BreakdownRow(
                    cost.provider() + "/" + model,
                    model,
                    cost.provider(),
                    cost.executions(),
                    line.subagents(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow ofDay(BreakdownLine<CostByDay> line) {
            CostByDay cost = line.cost();
            String day = cost.day().toString();
            return new BreakdownRow(
                    day,
                    day,
                    null,
                    cost.executions(),
                    line.subagents(),
                    cost.estimatedMicros(),
                    cost.actualMicros(),
                    cost.inputTokens(),
                    cost.outputTokens(),
                    round(cost.avgContextChars()),
                    cost.firstSeenAt(),
                    cost.lastSeenAt());
        }

        static BreakdownRow ofFingerprint(BreakdownLine<CostByFingerprint> line) {
            CostByFingerprint cost = line.cost();
            return new BreakdownRow(
                    cost.fingerprint(),
                    cost.fingerprint(),
                    null,
                    cost.executions(),
                    line.subagents(),
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
     * 로그인한 사용자 자신이 부른 스킬의 합계 한 줄이다.
     *
     * @param agentCode 에이전트 코드. 내부 값을 받지 않는 요청자에게는 null
     * @param lastConversationId 마지막 호출이 속한 대화의 공개 식별자. 그 대화를 지웠으면 null
     */
    public record MySkillUsageView(
            String agentCode,
            String agentName,
            String skillName,
            long count,
            Instant lastInvokedAt,
            UUID lastConversationId) {

        static MySkillUsageView from(UserSkillUsage usage, boolean internal) {
            return new MySkillUsageView(
                    internal ? usage.agentCode() : null,
                    usage.agentName(),
                    usage.skillName(),
                    usage.count(),
                    usage.lastInvokedAt(),
                    usage.lastConversationId());
        }
    }

    /**
     * 사용량 목록의 한 줄.
     *
     * @param conversationId 이 실행이 속한 대화의 공개 식별자. 대화 없이 돈 실행이면 null
     * @param hasChildren 이 실행이 부른 실행이 있는가. 화면이 트리로 들어갈 곳을 고를 때 쓴다
     * @param retryOfExecutionId 막혀서 넘어오며 이 실행이 대신한 직전 실행. 첫 시도면 null
     * @param skillNames 이 실행에서 쓴 스킬 이름. 없으면 빈 목록
     */
    public record ExecutionView(
            Long id,
            UUID conversationId,
            String agentCode,
            String agentName,
            String provider,
            String model,
            String reasoningEffort,
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
            boolean hasChildren,
            Long retryOfExecutionId,
            List<String> skillNames) {

        /**
         * 자식 여부와 스킬 이름을 받아서만 만든다.
         *
         * <p>{@code hasChildren} 을 거짓으로 채워 주는 짧은 형태를 두지 않는다. 그것을 부르면 자식이
         * 있는 실행이 목록에 표시 없이 보이고, 컴파일은 통과한다. 부르는 쪽이 자식을 셀지 정하게 한다.
         *
         * <p>{@code agent} 는 행이 없으면 null 이다. 그 줄은 에이전트 코드와 이름만 비운다.
         *
         * <p>{@code internal} 이 거짓이면 에이전트 코드, 모델, 토큰, 금액, 문맥 크기, 설정 구분값을 비운다.
         * 오류 코드와 걸린 시간과 스킬 이름은 그대로 싣는다.
         */
        static ExecutionView from(
                AgentExecution execution,
                Agent agent,
                UUID conversationPublicId,
                boolean hasChildren,
                List<String> skillNames,
                boolean internal) {
            return new ExecutionView(
                    execution.id(),
                    conversationPublicId,
                    !internal || agent == null ? null : agent.code(),
                    agent == null ? null : agent.name(),
                    internal ? execution.provider() : null,
                    internal ? execution.model() : null,
                    internal ? execution.reasoningEffort() : null,
                    internal ? execution.costMode().name() : null,
                    execution.status().name(),
                    execution.errorCode(),
                    internal ? execution.inputTokens() : null,
                    internal ? execution.cachedInputTokens() : null,
                    internal ? execution.outputTokens() : null,
                    internal ? execution.totalTokens() : null,
                    execution.latencyMs(),
                    internal ? execution.contextChars() : null,
                    internal ? execution.contextOmittedItems() : null,
                    internal ? execution.runtimeFingerprint() : null,
                    internal ? execution.instructionsHash() : null,
                    internal ? execution.estimatedCostMicros() : null,
                    internal ? execution.actualCostMicros() : null,
                    internal ? execution.costCurrency() : null,
                    internal ? execution.pricingVersion() : null,
                    execution.startedAt(),
                    hasChildren,
                    execution.retryOfExecutionId(),
                    skillNames);
        }
    }
}
