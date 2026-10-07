package com.bifos.assistant.proactive;

import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 행동 정책의 합성 입력이다. 기본값은 모든 조건을 갖춘 읽기 전용 후보다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class AutonomyFixtures {

    static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    static final Duration EVALUATION_AGE = Duration.ofHours(1);
    static final Duration EVIDENCE_AGE = Duration.ofHours(72);

    static Map<DecisionAxis, DecisionLevel> choices() {
        Map<DecisionAxis, DecisionLevel> choices = new EnumMap<>(DecisionAxis.class);
        choices.put(DecisionAxis.GOAL_ALIGNMENT, DecisionLevel.HIGH);
        choices.put(DecisionAxis.URGENCY, DecisionLevel.HIGH);
        choices.put(DecisionAxis.EXPECTED_BENEFIT, DecisionLevel.HIGH);
        choices.put(DecisionAxis.COST, DecisionLevel.LOW);
        choices.put(DecisionAxis.RISK, DecisionLevel.LOW);
        choices.put(DecisionAxis.EVIDENCE_QUALITY, DecisionLevel.HIGH);
        return choices;
    }

    static Map<DecisionAxis, DecisionConfidence> confidences(DecisionConfidence confidence) {
        Map<DecisionAxis, DecisionConfidence> confidences = new EnumMap<>(DecisionAxis.class);
        for (DecisionAxis axis : DecisionAxis.values()) {
            confidences.put(axis, confidence);
        }
        return confidences;
    }

    static Builder safe() {
        return new Builder();
    }

    static final class Builder {
        DecisionOutcome outcome = DecisionOutcome.EVALUATED;
        boolean replay;
        Instant evaluatedAt = NOW.minusSeconds(60);
        Instant asOf = NOW.minusSeconds(60);
        boolean judged = true;
        boolean current = true;
        String actionType = "ACTION";
        String sideEffect = "NONE";
        String candidateConfidence = "HIGH";
        Map<DecisionAxis, DecisionLevel> choices = choices();
        Map<DecisionAxis, DecisionConfidence> axisConfidences = confidences(DecisionConfidence.HIGH);
        DecisionConfidence judgementConfidence = DecisionConfidence.HIGH;
        Instant evidenceCheckedAt = NOW.minusSeconds(3600);
        boolean executionEnabled = true;
        boolean consented = true;
        boolean writesAllowed;
        CheckTrigger sourceTrigger = CheckTrigger.MANUAL;
        boolean startable = true;
        boolean alreadyExecuted;

        Builder outcome(DecisionOutcome value) {
            outcome = value;
            return this;
        }

        Builder replay() {
            replay = true;
            return this;
        }

        Builder evaluatedAt(Instant value) {
            evaluatedAt = value;
            asOf = value;
            return this;
        }

        Builder judged(boolean value) {
            judged = value;
            return this;
        }

        Builder current(boolean value) {
            current = value;
            return this;
        }

        Builder actionType(String value) {
            actionType = value;
            return this;
        }

        Builder sideEffect(String value) {
            sideEffect = value;
            return this;
        }

        Builder candidateConfidence(String value) {
            candidateConfidence = value;
            return this;
        }

        Builder axis(DecisionAxis axis, DecisionLevel level) {
            choices.put(axis, level);
            return this;
        }

        Builder confidence(DecisionConfidence value) {
            axisConfidences = confidences(value);
            judgementConfidence = value;
            return this;
        }

        Builder evidenceCheckedAt(Instant value) {
            evidenceCheckedAt = value;
            return this;
        }

        Builder executionEnabled(boolean value) {
            executionEnabled = value;
            return this;
        }

        Builder consented(boolean value) {
            consented = value;
            return this;
        }

        Builder writesAllowed(boolean value) {
            writesAllowed = value;
            return this;
        }

        Builder sourceTrigger(CheckTrigger value) {
            sourceTrigger = value;
            return this;
        }

        Builder startable(boolean value) {
            startable = value;
            return this;
        }

        Builder alreadyExecuted(boolean value) {
            alreadyExecuted = value;
            return this;
        }

        AutonomyInputs build() {
            return new AutonomyInputs(
                    outcome,
                    replay,
                    evaluatedAt,
                    asOf,
                    NOW,
                    judged,
                    current,
                    actionType,
                    sideEffect,
                    candidateConfidence,
                    choices,
                    axisConfidences,
                    judgementConfidence,
                    evidenceCheckedAt,
                    executionEnabled,
                    consented,
                    writesAllowed,
                    sourceTrigger,
                    startable,
                    alreadyExecuted);
        }
    }
}
