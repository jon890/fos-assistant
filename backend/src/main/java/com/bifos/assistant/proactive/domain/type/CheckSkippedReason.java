package com.bifos.assistant.proactive.domain.type;

/** 모델을 부르지 않고 먼저 살펴보기를 끝낸 까닭이다. */
public enum CheckSkippedReason {
    NO_CHANGE,
    UNREAD_REPORT,
    QUIET_HOURS
}
