package com.bifos.assistant.chat.domain.type;

/** 전달 시도 한 번의 상태다(ADR-070). */
public enum DeliveryAttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPED;

    /** 이 상태로 시도가 끝나면 묶음이 갖는 상태다. */
    public DeliveryStatus deliveryStatus() {
        return switch (this) {
            case RUNNING -> DeliveryStatus.DELIVERING;
            case SUCCEEDED -> DeliveryStatus.DELIVERED;
            case FAILED -> DeliveryStatus.FAILED;
            case STOPPED -> DeliveryStatus.STOPPED;
        };
    }
}
