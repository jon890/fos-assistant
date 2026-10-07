package com.bifos.assistant.proactive.domain.type;

/** 순서까지 받은 결과, 근거가 부족한 결과, 호출 실패, 후보 없음이다. */
public enum DecisionOutcome {
    RUNNING,
    EVALUATED,
    INSUFFICIENT_EVIDENCE,
    FALLBACK,
    EMPTY
}
