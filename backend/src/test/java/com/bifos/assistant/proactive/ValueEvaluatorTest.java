package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.proactive.application.DecisionProvider;
import com.bifos.assistant.proactive.application.ValueEvaluator;
import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueEvaluatorTest {

    private final ValueEvaluator evaluator = new ValueEvaluator();
    private final DecisionState state = DecisionFixtures.state();

    @Test
    @DisplayName("공고 마감 문제를 공부 문제보다 먼저 둔 까닭과 여섯 축이 함께 남는다")
    void keepsOrderingAndAxisReasons() {
        DecisionResult result = validate(DecisionFixtures.ordered(state));
        assertThat(result.outcome()).isEqualTo(DecisionOutcome.EVALUATED);
        assertThat(result.orderedCandidateIds()).containsExactly(11L, 12L);
        assertThat(result.explanation()).contains("마감 이틀", "실행 부담");
        assertThat(result.judgements()).allSatisfy(each -> {
            assertThat(each.axes()).hasSize(6);
            assertThat(each.axes()).allSatisfy(axis -> assertThat(axis.evidenceKeys()).isNotEmpty());
        });
    }

    @Test
    @DisplayName("낮은 확신은 축별 판단을 보존하지만 추천 순서를 비운다")
    void removesOrderingWhenConfidenceIsLow() {
        DecisionResult original = DecisionFixtures.ordered(state);
        CandidateJudgement first = original.judgements().getFirst();
        List<CandidateJudgement> judgements = List.of(new CandidateJudgement(first.candidateId(), first.axes(),
                DecisionConfidence.LOW, first.explanation()), original.judgements().getLast());
        DecisionResult result = validate(new DecisionResult(original.outcome(), judgements, original.orderedCandidateIds(),
                original.explanation(), null));
        assertThat(result.outcome()).isEqualTo(DecisionOutcome.INSUFFICIENT_EVIDENCE);
        assertThat(result.orderedCandidateIds()).isEmpty();
        assertThat(result.judgements()).hasSize(2);
    }

    @Test
    @DisplayName("없는 근거 키와 중복 축과 누락 후보와 잘못된 순서는 거절한다")
    void rejectsForgedProvenanceAndIncompleteOutput() {
        DecisionResult original = DecisionFixtures.ordered(state);
        CandidateJudgement first = original.judgements().getFirst();
        List<AxisJudgement> forged = new ArrayList<>(first.axes());
        AxisJudgement axis = forged.getFirst();
        forged.set(0, new AxisJudgement(axis.axis(), axis.choice(), axis.confidence(), axis.explanation(), List.of("other-user")));
        assertInvalid(new DecisionResult(original.outcome(), List.of(new CandidateJudgement(11L, forged,
                first.confidence(), first.explanation()), original.judgements().getLast()), original.orderedCandidateIds(), "근거", null));
        forged = new ArrayList<>(first.axes());
        forged.set(0, forged.getLast());
        assertInvalid(new DecisionResult(original.outcome(), List.of(new CandidateJudgement(11L, forged,
                first.confidence(), first.explanation()), original.judgements().getLast()), original.orderedCandidateIds(), "중복", null));
        assertInvalid(new DecisionResult(original.outcome(), List.of(first), List.of(11L, 12L), "누락", null));
        assertInvalid(new DecisionResult(original.outcome(), original.judgements(), List.of(11L, 11L), "중복 순서", null));
        assertInvalid(new DecisionResult(original.outcome(), original.judgements(), List.of(11L, 999L), "없는 후보", null));
    }

    @Test
    @DisplayName("provider 실패는 다른 모델을 부르지 않고 설명 가능한 실패로 닫는다")
    void fallsBackOnProviderFailure() {
        DecisionProvider provider = mock(DecisionProvider.class);
        when(provider.id()).thenReturn("fixture");
        DecisionRequest request = new DecisionRequest(null);
        when(provider.evaluate(state, ValueEvaluator.QUESTIONS, request)).thenThrow(new IllegalStateException("private failure"));
        DecisionResponse response = evaluator.evaluate(state, ValueEvaluator.QUESTIONS, provider, request);
        assertThat(response.result().failure()).isEqualTo(DecisionFailure.PROVIDER_FAILED);
        assertThat(response.result().orderedCandidateIds()).isEmpty();
        assertThat(response.result().explanation()).doesNotContain("private failure");
    }

    @Test
    @DisplayName("후보가 없으면 provider 호출 없이 침묵할 수 있는 EMPTY 결과다")
    void skipsProviderWhenCandidatesAreEmpty() {
        DecisionProvider provider = mock(DecisionProvider.class);
        when(provider.id()).thenReturn("fixture");
        DecisionResponse result = evaluator.evaluate(new DecisionState(1, DecisionFixtures.NOW, List.of()),
                ValueEvaluator.QUESTIONS, provider, new DecisionRequest(null));
        assertThat(result.result().outcome()).isEqualTo(DecisionOutcome.EMPTY);
        assertThat(result.result().orderedCandidateIds()).isEmpty();
    }

    private DecisionResult validate(DecisionResult result) {
        return evaluator.validate(state, ValueEvaluator.QUESTIONS, result);
    }

    private void assertInvalid(DecisionResult result) {
        assertThat(validate(result).failure()).isEqualTo(DecisionFailure.INVALID_RESULT);
    }
}
