package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionQuestion;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** provider 의 판단 모양과 provenance 를 검사한다. 숫자 가중치로 모델의 순서를 덮어쓰지 않는다. */
@Component
public class ValueEvaluator {

    public static final List<DecisionQuestion> QUESTIONS = List.of(
            new DecisionQuestion(DecisionAxis.GOAL_ALIGNMENT, "후보가 말한 목표에 얼마나 직접 닿는가. 목표가 실제로 있다는 확신과 구분한다"),
            new DecisionQuestion(DecisionAxis.URGENCY, "기준 시각에 지금 미루면 잃는 기회가 있는가. 확인 시각만으로 마감이나 긴급성을 추정하지 않는다"),
            new DecisionQuestion(DecisionAxis.EXPECTED_BENEFIT, "해결하면 얻을 효과가 얼마나 큰가. 후보가 말한 기대 효과는 가설이다"),
            new DecisionQuestion(DecisionAxis.COST, "사용자 주의와 실행의 시간·부담이 얼마나 큰가. 모르는 소요 시간과 금액을 만들지 않는다"),
            new DecisionQuestion(DecisionAxis.RISK, "부작용과 되돌리기 어려움이 얼마나 큰가. sideEffect는 힌트이며 권한이 아니다"),
            new DecisionQuestion(DecisionAxis.EVIDENCE_QUALITY, "근거 참조와 확인 시각이 충분한가. 원문 본문이 없어 내용이 맞는지는 다시 확인하지 못한다"));

    public DecisionResponse evaluate(
            DecisionState state, List<DecisionQuestion> questions, DecisionProvider provider, DecisionRequest request) {
        DecisionProviderInfo unavailable =
                new DecisionProviderInfo(provider.id(), "unknown", null, null, null, null, null);
        if (state.candidates().isEmpty()) {
            return new DecisionResponse(unavailable, DecisionResult.empty());
        }
        try {
            DecisionResponse response = provider.evaluate(state, questions, request);
            if (response == null
                    || response.provider() == null
                    || !provider.id().equals(response.provider().adapter())) {
                return new DecisionResponse(unavailable, DecisionResult.fallback(DecisionFailure.INVALID_RESULT));
            }
            return new DecisionResponse(response.provider(), validate(state, questions, response.result()));
        } catch (RuntimeException ex) {
            return new DecisionResponse(unavailable, DecisionResult.fallback(DecisionFailure.PROVIDER_FAILED));
        }
    }

    /** 불완전하거나 다른 후보를 가리키는 답은 전부 거절한다. 긴 설명을 잘라 근거 관계를 바꾸지 않는다. */
    public DecisionResult validate(DecisionState state, List<DecisionQuestion> questions, DecisionResult result) {
        try {
            require(result != null && result.outcome() != null);
            if (result.outcome() == DecisionOutcome.FALLBACK) {
                require(result.failure() != null
                        && result.judgements().isEmpty()
                        && result.orderedCandidateIds().isEmpty());
                return DecisionResult.fallback(result.failure());
            }
            require(result.outcome() == DecisionOutcome.EVALUATED
                    || result.outcome() == DecisionOutcome.INSUFFICIENT_EVIDENCE);
            require(result.failure() == null && text(result.explanation()));
            var candidates = state.candidates().stream()
                    .collect(Collectors.toMap(DecisionCandidate::candidateId, Function.identity()));
            Set<DecisionAxis> axes =
                    questions.stream().map(DecisionQuestion::axis).collect(Collectors.toSet());
            require(result.judgements().size() == candidates.size());
            Set<Long> seen = new HashSet<>();
            boolean uncertain = false;
            for (CandidateJudgement judgement : result.judgements()) {
                require(seen.add(judgement.candidateId()) && candidates.containsKey(judgement.candidateId()));
                uncertain |= validateCandidate(candidates.get(judgement.candidateId()), judgement, axes, state);
            }
            if (result.outcome() == DecisionOutcome.INSUFFICIENT_EVIDENCE || uncertain) {
                return new DecisionResult(
                        DecisionOutcome.INSUFFICIENT_EVIDENCE,
                        result.judgements(),
                        List.of(),
                        "근거나 판단의 확신이 부족해 순서를 정하지 않았다. " + result.explanation(),
                        null);
            }
            require(result.orderedCandidateIds().size() == candidates.size());
            require(new HashSet<>(result.orderedCandidateIds()).equals(candidates.keySet()));
            return result;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return DecisionResult.fallback(DecisionFailure.INVALID_RESULT);
        }
    }

    private boolean validateCandidate(
            DecisionCandidate candidate,
            CandidateJudgement judgement,
            Set<DecisionAxis> expectedAxes,
            DecisionState state) {
        require(judgement.confidence() != null && text(judgement.explanation()));
        require(judgement.axes().size() == expectedAxes.size());
        Set<String> evidenceKeys =
                candidate.evidence().stream().map(each -> each.topicKey()).collect(Collectors.toSet());
        Set<DecisionAxis> seen = new HashSet<>();
        boolean uncertain = judgement.confidence() == DecisionConfidence.LOW
                || evidenceKeys.isEmpty()
                || candidate.evidenceCheckedAt() == null
                || candidate.evidenceCheckedAt().isAfter(state.asOf());
        for (AxisJudgement axis : judgement.axes()) {
            require(axis.axis() != null && seen.add(axis.axis()) && expectedAxes.contains(axis.axis()));
            require(axis.choice() != null && axis.confidence() != null && text(axis.explanation()));
            require(new HashSet<>(axis.evidenceKeys()).size()
                    == axis.evidenceKeys().size());
            require(evidenceKeys.containsAll(axis.evidenceKeys()));
            if (axis.choice() == DecisionLevel.UNKNOWN) {
                require(axis.confidence() == DecisionConfidence.LOW);
            } else {
                require(!axis.evidenceKeys().isEmpty());
            }
            if (axis.axis() == DecisionAxis.EVIDENCE_QUALITY || axis.axis() == DecisionAxis.GOAL_ALIGNMENT) {
                uncertain |= axis.choice() == DecisionLevel.UNKNOWN || axis.confidence() == DecisionConfidence.LOW;
                uncertain |= axis.axis() == DecisionAxis.EVIDENCE_QUALITY && axis.choice() == DecisionLevel.LOW;
            }
        }
        return uncertain;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.length() <= 600;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("invalid decision evidence");
        }
    }
}
