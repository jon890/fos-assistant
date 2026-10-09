package com.bifos.assistant.attention.application.model;

/** 항목이 보인 까닭을 더하는 신호다. 뜻은 {@code docs/features/attention.md} 의 「왜 보였는가」 가 갖는다. */
public enum AttentionSignal {
    NOT_RETRIED,
    DELIVERY_NOT_DONE,
    EXPIRES_SOON,
    DUE_SOON,
    OVERDUE,
    LINKED_UPDATE,
    WAITING,
    LONG_RUNNING
}
