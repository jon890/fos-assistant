package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import java.util.List;

/** 후보 하나의 수준과 그 까닭이다. 까닭은 {@link AutonomyReason} 의 선언 순서다. */
public record AutonomyVerdict(AutonomyLevel level, List<AutonomyReason> reasons) {

    public AutonomyVerdict {
        reasons = List.copyOf(reasons);
    }
}
