package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.time.Instant;
import java.util.Map;

/**
 * 행동 정책이 후보 하나를 판정할 때 쓴 값이다. 판정 줄의 {@code inputs_json} 에 그대로 남겨 같은 입력으로 같은 수준이 나오는지 다시 볼 수 있게
 * 한다. 축 설명과 후보 글은 복제하지 않는다.
 *
 * @param judged 평가에 그 후보의 판단이 있다
 * @param candidateCurrent 지금의 후보 줄이 같은 살펴보기의 {@code ACCEPTED} 줄이고 스냅샷과 같다
 * @param actionType 모델이 쓴 행동 종류
 * @param sideEffect 모델이 쓴 부작용 힌트. 권한 판정이 아니며 수준을 낮추는 데만 쓴다
 * @param candidateConfidence 모델이 쓴 후보 자신의 확신
 * @param choices 축별 선택. 빠진 축은 모르는 것으로 본다
 * @param axisConfidences 축별 확신
 * @param judgementConfidence 후보의 종합 확신. 판단이 없으면 null
 * @param executionEnabled 설치 설정 {@code assistant.autonomy.execution-enabled}
 * @param userConsented 사용자가 읽기 전용 자동 실행에 동의했다
 * @param writesAllowed 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」
 * @param sourceTrigger 원천 살펴보기를 연 계기
 * @param agentStartable 요청자가 그 에이전트를 지금 시작할 수 있다
 * @param alreadyExecuted 그 원천 살펴보기의 실행 키가 이미 있다
 */
public record AutonomyInputs(
        DecisionOutcome evaluationOutcome,
        boolean replay,
        Instant evaluatedAt,
        Instant asOf,
        Instant decidedAt,
        boolean judged,
        boolean candidateCurrent,
        String actionType,
        String sideEffect,
        String candidateConfidence,
        Map<DecisionAxis, DecisionLevel> choices,
        Map<DecisionAxis, DecisionConfidence> axisConfidences,
        DecisionConfidence judgementConfidence,
        Instant evidenceCheckedAt,
        boolean executionEnabled,
        boolean userConsented,
        boolean writesAllowed,
        CheckTrigger sourceTrigger,
        boolean agentStartable,
        boolean alreadyExecuted) {

    public AutonomyInputs {
        choices = Map.copyOf(choices);
        axisConfidences = Map.copyOf(axisConfidences);
    }
}
