package com.bifos.assistant.proactive.domain.type;

/** 살펴보기 한 번의 상태다. {@code RUNNING} 으로 시작해 나머지 가운데 하나로 끝난다. */
public enum CheckStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 상한에 닿았거나 사용자가 멈췄다. */
    STOPPED
}
