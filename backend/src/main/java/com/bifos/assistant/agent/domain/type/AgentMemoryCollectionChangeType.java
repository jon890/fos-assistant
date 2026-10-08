package com.bifos.assistant.agent.domain.type;

/** 관리자가 에이전트의 받는 collection 한 줄을 어떻게 바꿨는지다(ADR-20261008 / agent-memory-grants-admin). */
public enum AgentMemoryCollectionChangeType {
    /** collection 을 붙였다. 기록의 민감 허용은 붙인 뒤의 값이다. */
    GRANTED,
    /** collection 을 뗐다. 기록의 민감 허용은 떼기 전의 값이다. */
    REVOKED,
    /** 붙인 채로 민감 허용만 바꿨다. 기록의 민감 허용은 바꾼 뒤의 값이다. */
    SENSITIVE_CHANGED
}
