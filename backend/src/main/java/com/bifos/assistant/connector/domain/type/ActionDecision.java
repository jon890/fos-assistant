package com.bifos.assistant.connector.domain.type;

/** 커넥터 도구 호출 하나의 판정이다(ADR-047). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다. */
public enum ActionDecision {
    ALLOWED,
    DENIED,
    NEEDS_APPROVAL
}
