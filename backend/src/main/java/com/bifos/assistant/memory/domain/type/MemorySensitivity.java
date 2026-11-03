package com.bifos.assistant.memory.domain.type;

/**
 * 문맥 항목의 민감도다. {@code SENSITIVE} 본문은 {@code instructions} 에 싣지 않는다. 기억에는 민감 구분이 없어 기억 항목은 늘
 * {@code NORMAL} 이다(ADR-20261103 / memory-sensitivity-removal). 위임 결과처럼 기억 밖의 항목이 {@code SENSITIVE} 를 쓴다.
 */
public enum MemorySensitivity {
    NORMAL,
    SENSITIVE
}
