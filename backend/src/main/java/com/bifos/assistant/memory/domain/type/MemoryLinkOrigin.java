package com.bifos.assistant.memory.domain.type;

/** 기억 연결을 누가 만들었는지다. */
public enum MemoryLinkOrigin {
    /** 코드가 임베딩으로 찾았다. */
    AUTO,
    /** 에이전트가 {@code memory_link} 로 맺었다. */
    AGENT,
    /** 사람이 화면에서 맺었다. */
    HUMAN
}
