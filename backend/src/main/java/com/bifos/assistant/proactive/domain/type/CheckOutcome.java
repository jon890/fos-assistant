package com.bifos.assistant.proactive.domain.type;

/** 성공한 살펴보기가 무엇을 냈는지다. {@code SUCCEEDED} 일 때만 채운다. */
public enum CheckOutcome {
    /** 결과 블록에 발견이 있다. */
    FINDINGS,
    /** 새로 알릴 것이 없다. */
    NOTHING_NEW,
    /** 답 끝의 결과 블록이 없거나 읽지 못했다(ADR-078). 결과를 그리지 않는다. */
    INVALID_RESULT
}
