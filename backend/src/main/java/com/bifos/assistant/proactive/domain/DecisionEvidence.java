package com.bifos.assistant.proactive.domain;

import java.util.List;

/** 같은 상태와 질문으로 provider 를 바꿔 비교할 수 있는 최소 기록이다. */
public record DecisionEvidence(DecisionState state, List<DecisionQuestion> questions,
        DecisionProviderInfo provider, DecisionResult result) {

    public DecisionEvidence {
        questions = List.copyOf(questions);
    }
}
