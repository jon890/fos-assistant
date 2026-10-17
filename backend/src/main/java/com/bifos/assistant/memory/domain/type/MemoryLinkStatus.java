package com.bifos.assistant.memory.domain.type;

/** 기억 연결의 상태다. 끊거나 거절해도 줄을 지우지 않는다. */
public enum MemoryLinkStatus {
    /** 사람이 받아들이기 전이다. 아무 동작도 바꾸지 않는다. */
    PROPOSED,
    /** 효력이 있다. */
    ACTIVE,
    /** 끊었거나 거절했다. 같은 쌍을 다시 맺으면 이 줄을 다시 쓴다. */
    REVOKED
}
