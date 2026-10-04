package com.bifos.assistant.context;

/** 문맥 항목의 내용이 지금도 참이라고 볼 수 있는지다(ADR-071). */
public enum ContextFreshness {
    /** 지금 상태로 본다. */
    FRESH,
    /** 오래돼 지금 상태와 다를 수 있다. */
    STALE,
    /** 언제 참이던 내용인지 모른다. */
    UNKNOWN
}
