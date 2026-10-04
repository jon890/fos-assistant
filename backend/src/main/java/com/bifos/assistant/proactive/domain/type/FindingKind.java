package com.bifos.assistant.proactive.domain.type;

/** 발견을 어느 쪽으로 그렸는지다. */
public enum FindingKind {
    /** 「새로 알릴 것」. */
    NEW,
    /** 「참고」. 까닭은 {@link FindingReason} 이 갖는다. */
    REFERENCE
}
