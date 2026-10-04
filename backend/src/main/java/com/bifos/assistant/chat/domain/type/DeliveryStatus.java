package com.bifos.assistant.chat.domain.type;

/** 전달 묶음의 상태다. 마지막 시도가 끝난 방식과 같다(ADR-075). */
public enum DeliveryStatus {
    /** 시도 하나가 도는 중이다. */
    DELIVERING,
    /** 부모 turn 이 답을 남겼다. */
    DELIVERED,
    /** 부모 turn 이 실패했다. */
    FAILED,
    /** 사용자가 부모 turn 을 중지했다. */
    STOPPED;

    /** 사용자가 다시 전달할 수 있는 상태인지다. */
    public boolean retryable() {
        return this == FAILED || this == STOPPED;
    }
}
