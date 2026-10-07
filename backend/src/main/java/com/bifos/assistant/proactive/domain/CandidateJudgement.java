package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import java.util.List;

/** 후보 하나의 축별 판단과 종합 설명이다. permission, approval, execute 칸은 없다. */
public record CandidateJudgement(
        Long candidateId, List<AxisJudgement> axes, DecisionConfidence confidence, String explanation) {

    public CandidateJudgement {
        axes = List.copyOf(axes);
    }
}
