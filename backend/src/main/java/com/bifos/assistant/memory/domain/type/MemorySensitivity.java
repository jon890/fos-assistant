package com.bifos.assistant.memory.domain.type;

/** 항목의 민감도다(ADR-052). {@code SENSITIVE} 는 그 collection 에서 민감 항목을 허용받은 에이전트만 받는다. */
public enum MemorySensitivity {
    NORMAL,
    SENSITIVE
}
