package com.bifos.assistant.agent.domain.type;

/** 관리자가 에이전트의 받는 collection 한 줄을 어떻게 바꿨는지다(ADR-20261008 / agent-memory-grants-admin). */
public enum AgentMemoryCollectionChangeType {
    /** collection 을 붙였다. */
    GRANTED,
    /** collection 을 뗐다. */
    REVOKED,
    /** 없어진 민감 허용을 바꾼 옛 기록이다. 새로 남기지 않고 최근 변경에도 내지 않는다. 읽어 오기 위해 값만 남긴다. */
    SENSITIVE_CHANGED
}
