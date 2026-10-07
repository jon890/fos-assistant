package com.bifos.assistant.proactive.domain.type;

/** 응답 본문과 예외 글을 복제하지 않고 남기는 실패 코드다. */
public enum DecisionFailure {
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    PROVIDER_FAILED,
    INVALID_RESULT,
    INTERRUPTED
}
