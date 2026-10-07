package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.AutonomyVerdict;
import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 후보마다 행동 수준을 정한다(ADR-20261007 autonomy-policy). DB 와 모델을 모르는 함수다.
 *
 * <p>규칙은 {@code docs/backend/autonomy-policy.md} 의 「까닭 코드와 수준」 표와 같다. 모델이 쓴 값(확신, 부작용 힌트, 축 판단)은 까닭을
 * 더할 수만 있다. 수준을 올리는 입력은 모두 Control Plane 의 기록이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AutonomyPolicy {

    /** 규칙을 바꾸면 올린다. 판정 줄에 남는다. */
    public static final int VERSION = 1;

    private static final List<DecisionAxis> AXES = List.of(DecisionAxis.values());

    private static final Set<DecisionOutcome> JUDGED_OUTCOMES =
            EnumSet.of(DecisionOutcome.EVALUATED, DecisionOutcome.INSUFFICIENT_EVIDENCE);

    /**
     * 추천 순서대로 받은 후보를 판정한다. {@code EXECUTE} 는 앞에서 처음 받은 후보 하나에만 남기고 나머지는 {@code EXECUTION_TAKEN} 으로
     * {@code SURFACE} 다.
     */
    public static List<AutonomyVerdict> decideAll(
            List<AutonomyInputs> ordered, Duration maxEvaluationAge, Duration maxEvidenceAge) {
        List<AutonomyVerdict> verdicts = new ArrayList<>();
        boolean taken = false;
        for (AutonomyInputs inputs : ordered) {
            Set<AutonomyReason> reasons = reasons(inputs, maxEvaluationAge, maxEvidenceAge);
            if (taken && level(reasons) == AutonomyLevel.EXECUTE) {
                reasons.add(AutonomyReason.EXECUTION_TAKEN);
            }
            AutonomyLevel level = level(reasons);
            if (level == AutonomyLevel.EXECUTE) {
                reasons.add(AutonomyReason.READ_ONLY_SAFE);
                taken = true;
            }
            verdicts.add(new AutonomyVerdict(level, List.copyOf(reasons)));
        }
        return List.copyOf(verdicts);
    }

    /** 걸린 까닭을 모두 모은다. 판정 순서와 상관없이 모아 기록이 판정의 근거를 빠짐없이 갖게 한다. */
    static EnumSet<AutonomyReason> reasons(AutonomyInputs in, Duration maxEvaluationAge, Duration maxEvidenceAge) {
        EnumSet<AutonomyReason> reasons = EnumSet.noneOf(AutonomyReason.class);
        DecisionLevel benefit = choice(in, DecisionAxis.EXPECTED_BENEFIT);
        DecisionLevel urgency = choice(in, DecisionAxis.URGENCY);
        DecisionLevel goal = choice(in, DecisionAxis.GOAL_ALIGNMENT);
        DecisionLevel cost = choice(in, DecisionAxis.COST);
        DecisionLevel risk = choice(in, DecisionAxis.RISK);
        DecisionLevel evidence = choice(in, DecisionAxis.EVIDENCE_QUALITY);

        if (!in.candidateCurrent()) {
            reasons.add(AutonomyReason.CANDIDATE_NOT_CURRENT);
        }
        if (!JUDGED_OUTCOMES.contains(in.evaluationOutcome()) || !in.judged()) {
            reasons.add(AutonomyReason.EVALUATION_NOT_USABLE);
        }
        if (benefit == DecisionLevel.LOW && (urgency == DecisionLevel.LOW || goal == DecisionLevel.LOW)) {
            reasons.add(AutonomyReason.LOW_VALUE);
        }
        if (in.alreadyExecuted()) {
            reasons.add(AutonomyReason.ALREADY_EXECUTED);
        }

        if (in.evaluationOutcome() == DecisionOutcome.INSUFFICIENT_EVIDENCE || evidence == DecisionLevel.LOW) {
            reasons.add(AutonomyReason.INSUFFICIENT_EVIDENCE);
        }
        if (lowConfidence(in)) {
            reasons.add(AutonomyReason.LOW_CONFIDENCE);
        }
        if (in.judged() && AXES.stream().anyMatch(axis -> choice(in, axis) == DecisionLevel.UNKNOWN)) {
            reasons.add(AutonomyReason.UNKNOWN_JUDGEMENT);
        }
        if (olderThan(in.evaluatedAt(), in.decidedAt(), maxEvaluationAge)
                || olderThan(in.asOf(), in.decidedAt(), maxEvaluationAge)) {
            reasons.add(AutonomyReason.STALE_EVALUATION);
        }
        if (olderThan(in.evidenceCheckedAt(), in.decidedAt(), maxEvidenceAge)) {
            reasons.add(AutonomyReason.STALE_EVIDENCE);
        }
        if (in.replay()) {
            reasons.add(AutonomyReason.REPLAY_INPUT);
        }
        if ("QUESTION".equals(in.actionType())) {
            reasons.add(AutonomyReason.QUESTION_FOR_USER);
        } else if (!"ACTION".equals(in.actionType())) {
            reasons.add(AutonomyReason.ACTION_UNDECLARED);
        }

        // 부작용 힌트는 모델이 쓴 값이다. NONE 은 실행 조건의 하나일 뿐이고, 나머지는 모두 승인 쪽으로 보낸다.
        String sideEffect = in.sideEffect();
        if ("EXTERNAL".equals(sideEffect)) {
            reasons.add(AutonomyReason.EXTERNAL_WRITE_REQUIRES_APPROVAL);
        } else if ("INTERNAL".equals(sideEffect)) {
            reasons.add(AutonomyReason.INTERNAL_WRITE_REQUIRES_APPROVAL);
        } else if (!"NONE".equals(sideEffect)) {
            reasons.add(AutonomyReason.SIDE_EFFECT_UNDECLARED);
        }
        if (risk == DecisionLevel.MEDIUM || risk == DecisionLevel.HIGH) {
            reasons.add(AutonomyReason.RISK_NOT_LOW);
        }

        if (benefit != DecisionLevel.HIGH || goal == DecisionLevel.LOW || goal == DecisionLevel.UNKNOWN) {
            reasons.add(AutonomyReason.VALUE_NOT_HIGH);
        }
        if (cost != DecisionLevel.LOW) {
            reasons.add(AutonomyReason.COST_NOT_LOW);
        }
        if (!in.executionEnabled() || !in.userConsented()) {
            reasons.add(AutonomyReason.USER_AUTONOMY_DISABLED);
        }
        if (in.writesAllowed()) {
            reasons.add(AutonomyReason.WRITE_BOUNDARY_OPEN);
        }
        if (in.sourceTrigger() == CheckTrigger.AUTONOMY) {
            reasons.add(AutonomyReason.SOURCE_IS_AUTONOMOUS);
        }
        if (!in.agentStartable()) {
            reasons.add(AutonomyReason.AGENT_NOT_STARTABLE);
        }
        return reasons;
    }

    /** 앞 묶음에 하나라도 있으면 뒤 묶음을 보지 않는다. 근거가 약한 후보에는 승인을 묻지 않는다. */
    static AutonomyLevel level(Set<AutonomyReason> reasons) {
        if (has(reasons, AutonomyReason.Group.IGNORE)) {
            return AutonomyLevel.IGNORE;
        }
        if (has(reasons, AutonomyReason.Group.WEAK_BASIS)) {
            return AutonomyLevel.SURFACE;
        }
        if (has(reasons, AutonomyReason.Group.APPROVAL)) {
            return AutonomyLevel.ASK_APPROVAL;
        }
        if (has(reasons, AutonomyReason.Group.EXECUTION_BLOCKED)) {
            return AutonomyLevel.SURFACE;
        }
        return AutonomyLevel.EXECUTE;
    }

    private static boolean has(Set<AutonomyReason> reasons, AutonomyReason.Group group) {
        return reasons.stream().anyMatch(reason -> reason.group() == group);
    }

    /** 빠진 축은 모르는 것이다. */
    private static DecisionLevel choice(AutonomyInputs in, DecisionAxis axis) {
        return in.choices().getOrDefault(axis, DecisionLevel.UNKNOWN);
    }

    /** 판단이 없으면 확신을 따지지 않는다. 그때는 {@code EVALUATION_NOT_USABLE} 이 이미 다루지 않음으로 보낸다. */
    private static boolean lowConfidence(AutonomyInputs in) {
        boolean candidateConfident =
                "MEDIUM".equals(in.candidateConfidence()) || "HIGH".equals(in.candidateConfidence());
        if (!candidateConfident) {
            return true;
        }
        if (!in.judged()) {
            return false;
        }
        return in.judgementConfidence() != DecisionConfidence.MEDIUM
                        && in.judgementConfidence() != DecisionConfidence.HIGH
                || AXES.stream()
                        .anyMatch(axis -> in.axisConfidences().get(axis) != DecisionConfidence.MEDIUM
                                && in.axisConfidences().get(axis) != DecisionConfidence.HIGH);
    }

    /** 시각이 없으면 오래된 것으로 본다. 기준보다 뒤의 시각도 믿지 않고 오래된 것으로 본다. */
    private static boolean olderThan(Instant at, Instant now, Duration maxAge) {
        if (at == null || at.isAfter(now)) {
            return true;
        }
        return Duration.between(at, now).compareTo(maxAge) > 0;
    }
}
