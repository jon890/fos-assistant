package com.bifos.assistant.admin.usage.presentation;

import com.bifos.assistant.usage.summary.application.dto.BreakdownLine;
import com.bifos.assistant.usage.execution.domain.CostByAgent;
import com.bifos.assistant.usage.execution.domain.CostByDay;
import com.bifos.assistant.usage.execution.domain.CostByFingerprint;
import com.bifos.assistant.usage.execution.domain.CostByModel;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 비용 집계 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UsageBreakdownAdminDtos {

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
            String label = cost.agentId() == null ? "시스템 판단" : cost.agentName() == null ? "지운 에이전트" : cost.agentName();
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
}
