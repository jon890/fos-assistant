package com.bifos.assistant.proactive;

import static com.bifos.assistant.proactive.AutonomyFixtures.EVALUATION_AGE;
import static com.bifos.assistant.proactive.AutonomyFixtures.EVIDENCE_AGE;
import static com.bifos.assistant.proactive.AutonomyFixtures.NOW;
import static com.bifos.assistant.proactive.AutonomyFixtures.safe;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.application.AutonomyPolicy;
import com.bifos.assistant.proactive.application.model.AutonomyVerdict;
import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AutonomyPolicyTest {

    private static AutonomyVerdict decide(AutonomyInputs inputs) {
        return AutonomyPolicy.decideAll(List.of(inputs), EVALUATION_AGE, EVIDENCE_AGE)
                .getFirst();
    }

    @Test
    @DisplayName("모든 조건을 갖춘 읽기 전용 후보만 EXECUTE 와 READ_ONLY_SAFE 를 받는다")
    void executesOnlyFullySafeCandidate() {
        AutonomyVerdict verdict = decide(safe().build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.EXECUTE);
        assertThat(verdict.reasons()).containsExactly(AutonomyReason.READ_ONLY_SAFE);
    }

    @Test
    @DisplayName("가치와 확신이 모두 높아도 외부 쓰기 후보는 EXECUTE 가 아니라 ASK_APPROVAL 이다")
    void highValueExternalWriteAsksApproval() {
        AutonomyVerdict verdict = decide(safe().sideEffect("EXTERNAL").build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.ASK_APPROVAL);
        assertThat(verdict.reasons())
                .contains(AutonomyReason.EXTERNAL_WRITE_REQUIRES_APPROVAL)
                .doesNotContain(AutonomyReason.READ_ONLY_SAFE);
    }

    @Test
    @DisplayName("부작용 힌트가 NONE 이 아니면 확신, 동의, 설치 설정의 어떤 조합에서도 EXECUTE 로 가지 않는다")
    void modelConfidenceNeverWidensWrites() {
        for (String sideEffect : new String[] {"EXTERNAL", "INTERNAL", "DELETE", "", null}) {
            for (DecisionConfidence confidence : DecisionConfidence.values()) {
                for (boolean consented : new boolean[] {true, false}) {
                    AutonomyVerdict verdict = decide(safe().sideEffect(sideEffect)
                            .confidence(confidence)
                            .consented(consented)
                            .executionEnabled(true)
                            .build());
                    assertThat(verdict.level())
                            .as("sideEffect=%s confidence=%s consented=%s", sideEffect, confidence, consented)
                            .isNotEqualTo(AutonomyLevel.EXECUTE);
                }
            }
        }
    }

    @Test
    @DisplayName("사용자 동의가 꺼져 있어도 외부 쓰기 후보는 승인 대상으로 남는다")
    void approvalOutranksDisabledAutonomy() {
        AutonomyVerdict verdict =
                decide(safe().sideEffect("EXTERNAL").consented(false).build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.ASK_APPROVAL);
        assertThat(verdict.reasons()).contains(AutonomyReason.USER_AUTONOMY_DISABLED);
    }

    @Test
    @DisplayName("내부 쓰기, 모르는 부작용, 낮지 않은 위험은 승인 대상이다")
    void otherWritesAskApproval() {
        assertThat(decide(safe().sideEffect("INTERNAL").build()).reasons())
                .contains(AutonomyReason.INTERNAL_WRITE_REQUIRES_APPROVAL);
        assertThat(decide(safe().sideEffect("SEND").build()).reasons()).contains(AutonomyReason.SIDE_EFFECT_UNDECLARED);
        AutonomyVerdict risky =
                decide(safe().axis(DecisionAxis.RISK, DecisionLevel.MEDIUM).build());
        assertThat(risky.level()).isEqualTo(AutonomyLevel.ASK_APPROVAL);
        assertThat(risky.reasons()).contains(AutonomyReason.RISK_NOT_LOW);
    }

    @ParameterizedTest
    @EnumSource(DecisionAxis.class)
    @DisplayName("한 축이라도 UNKNOWN 이면 SURFACE 다")
    void unknownAxisSurfaces(DecisionAxis axis) {
        AutonomyVerdict verdict =
                decide(safe().axis(axis, DecisionLevel.UNKNOWN).build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(verdict.reasons()).contains(AutonomyReason.UNKNOWN_JUDGEMENT);
    }

    @ParameterizedTest
    @EnumSource(
            value = DecisionOutcome.class,
            names = {"FALLBACK", "EMPTY", "RUNNING"})
    @DisplayName("판단하지 못한 평가는 외부 쓰기 후보여도 IGNORE 다")
    void unusableEvaluationIgnores(DecisionOutcome outcome) {
        AutonomyVerdict verdict =
                decide(safe().outcome(outcome).sideEffect("EXTERNAL").build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.IGNORE);
        assertThat(verdict.reasons()).contains(AutonomyReason.EVALUATION_NOT_USABLE);
    }

    @Test
    @DisplayName("근거 부족 평가와 낮은 확신은 SURFACE 다")
    void weakBasisSurfaces() {
        AutonomyVerdict insufficient =
                decide(safe().outcome(DecisionOutcome.INSUFFICIENT_EVIDENCE).build());
        assertThat(insufficient.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(insufficient.reasons()).contains(AutonomyReason.INSUFFICIENT_EVIDENCE);

        AutonomyVerdict low = decide(safe().confidence(DecisionConfidence.LOW).build());
        assertThat(low.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(low.reasons()).contains(AutonomyReason.LOW_CONFIDENCE);

        assertThat(decide(safe().candidateConfidence("LOW").build()).reasons()).contains(AutonomyReason.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("replay 평가는 다른 조건이 모두 맞아도 EXECUTE 로 가지 않는다")
    void replayNeverExecutes() {
        AutonomyVerdict verdict = decide(safe().replay().build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(verdict.reasons()).contains(AutonomyReason.REPLAY_INPUT);
    }

    @Test
    @DisplayName("오래된 평가와 오래된 근거, 미래 시각의 근거는 EXECUTE 로 가지 않는다")
    void staleInputsNeverExecute() {
        assertThat(decide(safe().evaluatedAt(NOW.minus(EVALUATION_AGE).minusSeconds(1))
                                .build())
                        .reasons())
                .contains(AutonomyReason.STALE_EVALUATION);
        assertThat(decide(safe().evidenceCheckedAt(NOW.minus(EVIDENCE_AGE).minusSeconds(1))
                                .build())
                        .reasons())
                .contains(AutonomyReason.STALE_EVIDENCE);
        assertThat(decide(safe().evidenceCheckedAt(null).build()).level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(decide(safe().evidenceCheckedAt(NOW.plusSeconds(60)).build()).level())
                .isEqualTo(AutonomyLevel.SURFACE);
    }

    @Test
    @DisplayName("질문 후보는 사용자에게 보일 뿐 실행하지 않는다")
    void questionSurfaces() {
        AutonomyVerdict verdict = decide(safe().actionType("QUESTION").build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(verdict.reasons()).contains(AutonomyReason.QUESTION_FOR_USER);
    }

    @Test
    @DisplayName("기대 효과가 낮고 급하지 않으면 IGNORE 다")
    void lowValueIgnores() {
        AutonomyVerdict verdict = decide(safe().axis(DecisionAxis.EXPECTED_BENEFIT, DecisionLevel.LOW)
                .axis(DecisionAxis.URGENCY, DecisionLevel.LOW)
                .build());

        assertThat(verdict.level()).isEqualTo(AutonomyLevel.IGNORE);
        assertThat(verdict.reasons()).contains(AutonomyReason.LOW_VALUE);
    }

    @Test
    @DisplayName("설치 설정이나 사용자 동의가 꺼져 있으면 USER_AUTONOMY_DISABLED 로 SURFACE 다")
    void disabledAutonomySurfaces() {
        for (AutonomyInputs inputs : List.of(
                safe().executionEnabled(false).build(), safe().consented(false).build())) {
            AutonomyVerdict verdict = decide(inputs);
            assertThat(verdict.level()).isEqualTo(AutonomyLevel.SURFACE);
            assertThat(verdict.reasons()).containsExactly(AutonomyReason.USER_AUTONOMY_DISABLED);
        }
    }

    @Test
    @DisplayName("쓰기 허용 에이전트, 자동 실행의 결과, 시작할 수 없는 에이전트는 실행하지 않는다")
    void executionBoundaryBlocks() {
        assertThat(decide(safe().writesAllowed(true).build()).reasons())
                .containsExactly(AutonomyReason.WRITE_BOUNDARY_OPEN);
        assertThat(decide(safe().sourceTrigger(CheckTrigger.AUTONOMY).build()).reasons())
                .containsExactly(AutonomyReason.SOURCE_IS_AUTONOMOUS);
        assertThat(decide(safe().startable(false).build()).reasons())
                .containsExactly(AutonomyReason.AGENT_NOT_STARTABLE);
    }

    @Test
    @DisplayName("지금의 후보가 아니거나 이미 실행한 원천이면 IGNORE 다")
    void notCurrentOrExecutedIgnores() {
        assertThat(decide(safe().current(false).build()).level()).isEqualTo(AutonomyLevel.IGNORE);
        AutonomyVerdict executed = decide(safe().alreadyExecuted(true).build());
        assertThat(executed.level()).isEqualTo(AutonomyLevel.IGNORE);
        assertThat(executed.reasons()).containsExactly(AutonomyReason.ALREADY_EXECUTED);
    }

    @Test
    @DisplayName("한 판정에서 EXECUTE 는 추천 순서가 앞선 후보 하나뿐이다")
    void onlyFirstCandidateExecutes() {
        List<AutonomyVerdict> verdicts = AutonomyPolicy.decideAll(
                List.of(safe().sideEffect("EXTERNAL").build(), safe().build(), safe().build()),
                EVALUATION_AGE,
                EVIDENCE_AGE);

        assertThat(verdicts)
                .extracting(AutonomyVerdict::level)
                .containsExactly(AutonomyLevel.ASK_APPROVAL, AutonomyLevel.EXECUTE, AutonomyLevel.SURFACE);
        assertThat(verdicts.get(2).reasons()).containsExactly(AutonomyReason.EXECUTION_TAKEN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("같은 입력이면 같은 수준과 같은 까닭이 나온다")
    void deterministic(boolean external) {
        AutonomyInputs inputs =
                safe().sideEffect(external ? "EXTERNAL" : "NONE").build();

        assertThat(decide(inputs)).isEqualTo(decide(inputs));
    }
}
