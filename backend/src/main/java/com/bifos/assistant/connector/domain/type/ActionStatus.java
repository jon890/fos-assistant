package com.bifos.assistant.connector.domain.type;

/**
 * 승인이 필요했던 호출의 승인 줄 상태다(ADR-050). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>승인 줄이 아닌 판정 줄은 이 값을 비운다.
 */
public enum ActionStatus {
    PENDING,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REJECTED,
    EXPIRED
}
