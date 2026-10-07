package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import java.util.List;

/** 근거 키는 해당 후보가 가진 검증된 발견만 참조한다. */
public record AxisJudgement(DecisionAxis axis, DecisionLevel choice, DecisionConfidence confidence,
        String explanation, List<String> evidenceKeys) {

    public AxisJudgement {
        evidenceKeys = List.copyOf(evidenceKeys);
    }
}
