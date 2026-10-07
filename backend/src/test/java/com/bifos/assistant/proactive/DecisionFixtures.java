package com.bifos.assistant.proactive;

import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** #213 의 급한 공고와 다음 분기 공부 문제를 잇는 합성 판단 fixture 다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class DecisionFixtures {

    static final Instant NOW = Instant.parse("2026-10-07T00:03:00Z");

    static DecisionState state() {
        return new DecisionState(1, NOW, List.of(
                candidate(11L, "position:deadline:example", "관심 공고가 이틀 뒤 마감되지만 지원 여부를 정하지 않았다",
                        "백엔드 이직 준비", "지원 여부를 정할까요", "마감 전에 기회를 놓치지 않는다", "NONE"),
                candidate(12L, "study:exactly-once-gap", "면접에서 정확히 한 번 처리를 설명할 근거가 부족하다",
                        "다음 분기 면접 준비", "정확히 한 번 처리 예제를 돌려 보기", "설계 근거를 설명한다", "INTERNAL")));
    }

    static DecisionCandidate candidate(Long id, String key, String problem, String goal,
            String action, String benefit, String sideEffect) {
        return new DecisionCandidate(id, key, problem, goal, "NONE".equals(sideEffect) ? "QUESTION" : "ACTION", action, "HIGH", benefit, sideEffect, null,
                List.of(new ProblemEvidence(key, "https://docs.example.com/" + id, NOW.minusSeconds(30))), NOW.minusSeconds(30));
    }

    static DecisionResult ordered(DecisionState state) {
        List<CandidateJudgement> judgements = state.candidates().stream().map(candidate -> new CandidateJudgement(
                candidate.candidateId(), axes(candidate), DecisionConfidence.HIGH,
                candidate.problemKey().startsWith("position") ? "마감 전에 지원 의사를 확인할 가치가 크다" : "다음 분기 공부에 도움이 된다"))
                .toList();
        return new DecisionResult(DecisionOutcome.EVALUATED, judgements,
                state.candidates().stream().map(DecisionCandidate::candidateId).toList(),
                "목표 적합성과 효과는 둘 다 높지만, 공고는 마감 이틀 전이라 먼저 의사를 확인한다. 공부는 실행 부담이 더 크다", null);
    }

    private static List<AxisJudgement> axes(DecisionCandidate candidate) {
        boolean urgent = candidate.problemKey().startsWith("position");
        return Arrays.stream(DecisionAxis.values()).map(axis -> new AxisJudgement(axis,
                switch (axis) {
                    case URGENCY -> urgent ? DecisionLevel.HIGH : DecisionLevel.LOW;
                    case COST -> urgent ? DecisionLevel.LOW : DecisionLevel.MEDIUM;
                    case RISK -> DecisionLevel.LOW;
                    default -> DecisionLevel.HIGH;
                }, DecisionConfidence.HIGH,
                axis == DecisionAxis.URGENCY ? (urgent ? "후보가 마감을 이틀 뒤라고 명시했다" : "목표 시점은 다음 분기다") : "후보의 목표와 행동을 근거로 비교했다",
                List.of(candidate.evidence().getFirst().topicKey()))).toList();
    }
}
