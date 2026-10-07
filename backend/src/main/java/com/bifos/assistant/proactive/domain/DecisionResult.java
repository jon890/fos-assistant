package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.List;

/** 순서는 모델의 추천이다. 판단 불가와 실패는 빈 순서이며 후보 식별자를 정렬해 대신 추천하지 않는다. */
public record DecisionResult(
        DecisionOutcome outcome,
        List<CandidateJudgement> judgements,
        List<Long> orderedCandidateIds,
        String explanation,
        DecisionFailure failure) {

    public DecisionResult {
        judgements = List.copyOf(judgements);
        orderedCandidateIds = List.copyOf(orderedCandidateIds);
    }

    public static DecisionResult fallback(DecisionFailure failure) {
        return new DecisionResult(DecisionOutcome.FALLBACK, List.of(), List.of(), "판단하지 못해 순서를 정하지 않았다", failure);
    }

    public static DecisionResult empty() {
        return new DecisionResult(DecisionOutcome.EMPTY, List.of(), List.of(), "평가할 문제 후보가 없다", null);
    }

    public static DecisionResult running() {
        return new DecisionResult(DecisionOutcome.RUNNING, List.of(), List.of(), "평가 중이다", null);
    }
}
