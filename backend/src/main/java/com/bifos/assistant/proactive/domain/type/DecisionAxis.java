package com.bifos.assistant.proactive.domain.type;

/** 가치 판단의 여섯 축이다. 비용은 사용자 주의와 실행 부담을 함께 본다. */
public enum DecisionAxis {
    GOAL_ALIGNMENT,
    URGENCY,
    EXPECTED_BENEFIT,
    COST,
    RISK,
    EVIDENCE_QUALITY
}
