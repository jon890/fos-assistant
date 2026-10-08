package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import java.util.List;

/**
 * 관리자 화면이 읽는 가치 평가 묶음이다.
 *
 * @param check 고른 살펴보기. 고를 것이 없으면 null
 * @param acceptedCandidates 그 살펴보기의 받아들인 문제 후보 수
 * @param evaluation 그 살펴보기의 가장 최근 평가. 아직 평가하지 않았으면 null
 * @param decisions 그 평가의 판정 가운데 마지막으로 함께 남긴 묶음. 판정하지 않았으면 빈 목록
 */
public record EvaluationOverview(
        ProactiveCheck check, int acceptedCandidates, ValueEvaluation evaluation, List<AutonomyDecision> decisions) {

    public EvaluationOverview {
        decisions = List.copyOf(decisions);
    }

    /** 고른 살펴보기가 없는 묶음이다. */
    public static EvaluationOverview none() {
        return new EvaluationOverview(null, 0, null, List.of());
    }
}
