package com.bifos.assistant.memory.domain.type;

/** 에이전트가 {@code memory_remember} 로 남긴 기록이 무엇을 했는지다(ADR-092). */
public enum MemoryCaptureKind {
    /** 바로 저장해 새 항목을 만들었다. 되돌리면 그 항목을 지운다. */
    CREATED,
    /** 바로 저장해 기존 항목의 본문을 고쳤다. 되돌리면 고치기 전의 판으로 돌린다. */
    UPDATED,
    /** 제안으로 남겼다. 사람이 받아들이거나 거절한다. */
    PROPOSED
}
