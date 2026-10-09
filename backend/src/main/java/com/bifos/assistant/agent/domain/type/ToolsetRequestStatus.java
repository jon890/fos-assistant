package com.bifos.assistant.agent.domain.type;

/** 도구 사용 요청의 상태다. 끝난 요청은 권한을 회수하는 동작과 무관하다. */
public enum ToolsetRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED
}
