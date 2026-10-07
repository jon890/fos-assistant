package com.bifos.assistant.proactive.eval;

import com.bifos.assistant.proactive.application.DecisionProvider;
import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionQuestion;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 모델을 부르지 않는 결정적 provider 다. 같은 {@link DecisionState} 를 받으면 fixture 에 기록한 판단을 그대로 낸다.
 *
 * <p>실제 adapter 와 같은 port 만 구현한다. 그래서 provider 를 바꿔도 문제 찾기, 검사, 행동 정책, Hermes 실행 경로는 그대로다.
 * 실패하는 모드는 실제 adapter 가 내는 실패 모양(준비 안 됨, timeout, 예외, 형식 오류)을 흉내 낸다.
 */
public final class ReplayDecisionProvider implements DecisionProvider {

    enum Mode {
        /** fixture 의 판단 기록을 낸다. */
        REPLAY,
        /** 판단 profile 이 준비되지 않은 adapter 처럼 {@code PROVIDER_UNAVAILABLE} 을 낸다. */
        UNAVAILABLE,
        /** 대기 한도를 넘긴 adapter 처럼 {@code TIMEOUT} 을 낸다. */
        TIMEOUT,
        /** adapter 안에서 예외가 난다. */
        ERROR,
        /** 후보 하나를 빠뜨린 판단을 낸다. 검사가 거절해야 한다. */
        INVALID
    }

    private final String id;
    private final Mode mode;
    private final EvalDataset dataset;
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * 평가 검사가 쓰는 provider 다. 공통 검사 기반이 빈으로 둔다. 운영의 판단 경로는 provider 를 id 로만 고르므로
     * {@code fixture-} 로 시작하는 이 id 를 부르지 않는 검사에는 영향이 없다.
     */
    public static ReplayDecisionProvider forEval(String id, String mode) {
        return new ReplayDecisionProvider(id, Mode.valueOf(mode), DATASET);
    }

    private static final EvalDataset DATASET = EvalDataset.load();

    ReplayDecisionProvider(String id, Mode mode, EvalDataset dataset) {
        this.id = id;
        this.mode = mode;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return id;
    }

    /** 모델 호출 수다. 후보가 없으면 검사가 provider 를 부르지 않는다. */
    int calls() {
        return calls.get();
    }

    @Override
    public DecisionResponse evaluate(DecisionState state, List<DecisionQuestion> questions, DecisionRequest request) {
        calls.incrementAndGet();
        return switch (mode) {
            case REPLAY -> response(replay(state, questions));
            case UNAVAILABLE -> response(DecisionResult.fallback(DecisionFailure.PROVIDER_UNAVAILABLE));
            case TIMEOUT -> response(DecisionResult.fallback(DecisionFailure.TIMEOUT));
            case ERROR -> throw new IllegalStateException("fixture provider failure");
            case INVALID ->
                response(new DecisionResult(
                        DecisionOutcome.EVALUATED,
                        List.of(),
                        state.candidates().stream()
                                .map(DecisionCandidate::candidateId)
                                .toList(),
                        "후보를 빠뜨린 판단",
                        null));
        };
    }

    private DecisionResponse response(DecisionResult result) {
        String model = dataset.providers().containsKey(id)
                ? dataset.providers().get(id).model()
                : id;
        return new DecisionResponse(
                new DecisionProviderInfo(id, "fixture-1", "fixture", model, "fixture", model, null), result);
    }

    private DecisionResult replay(DecisionState state, List<DecisionQuestion> questions) {
        List<CandidateJudgement> judgements = new ArrayList<>();
        List<Ranked> ranked = new ArrayList<>();
        for (DecisionCandidate candidate : state.candidates()) {
            EvalDataset.Recorded recorded = dataset.recorded(id, candidate.problemKey())
                    .orElseThrow(() -> new IllegalStateException("판단 기록이 없다: " + candidate.problemKey()));
            judgements.add(judgement(candidate, recorded.judgement(), questions));
            ranked.add(new Ranked(candidate.candidateId(), recorded.rank()));
        }
        List<Long> ordered = ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::rank))
                .map(Ranked::candidateId)
                .toList();
        return new DecisionResult(DecisionOutcome.EVALUATED, judgements, ordered, "fixture 의 판단 기록 순서", null);
    }

    /** 근거 키는 후보가 가진 것을 그대로 붙인다. 모르는 축은 근거 없이 낮은 확신으로 둔다. */
    private static CandidateJudgement judgement(
            DecisionCandidate candidate, EvalDataset.Judgement recorded, List<DecisionQuestion> questions) {
        List<String> evidenceKeys =
                candidate.evidence().stream().map(ProblemEvidence::topicKey).toList();
        List<AxisJudgement> axes = questions.stream()
                .map(question -> {
                    DecisionLevel choice = recorded.axes().getOrDefault(question.axis(), DecisionLevel.UNKNOWN);
                    boolean unknown = choice == DecisionLevel.UNKNOWN;
                    return new AxisJudgement(
                            question.axis(),
                            choice,
                            unknown ? DecisionConfidence.LOW : recorded.confidence(),
                            "fixture 판단",
                            unknown ? List.of() : evidenceKeys);
                })
                .toList();
        return new CandidateJudgement(candidate.candidateId(), axes, recorded.confidence(), "fixture 판단");
    }

    private record Ranked(Long candidateId, int rank) {}
}
