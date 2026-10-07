package com.bifos.assistant.proactive.domain;

import java.time.Instant;
import java.util.List;

/** 받아들인 문제 후보의 평가 입력이다. 전체 Memory, 원문 본문, 대화 이력은 복제하지 않는다. */
public record DecisionCandidate(
        Long candidateId,
        String problemKey,
        String problem,
        String relatedGoal,
        String actionType,
        String actionText,
        String confidence,
        String expectedBenefit,
        String sideEffect,
        String risk,
        List<ProblemEvidence> evidence,
        Instant evidenceCheckedAt) {

    public DecisionCandidate {
        evidence = List.copyOf(evidence);
    }

    public static DecisionCandidate from(ProactiveCheckProblem problem) {
        return new DecisionCandidate(
                problem.id(),
                problem.problemKey(),
                problem.problem(),
                problem.relatedGoal(),
                problem.actionType(),
                problem.actionText(),
                problem.confidence(),
                problem.expectedBenefit(),
                problem.sideEffect(),
                problem.risk(),
                problem.evidence(),
                problem.evidenceCheckedAt());
    }
}
