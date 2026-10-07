package com.bifos.assistant.proactive.domain;

import java.time.Instant;
import java.util.List;

/** replay 는 후보와 기준 시각을 모두 고정한다. 새 근거를 읽거나 현재 시각으로 바꾸지 않는다. */
public record DecisionState(int version, Instant asOf, List<DecisionCandidate> candidates) {

    public DecisionState {
        candidates = List.copyOf(candidates);
    }
}
