package com.bifos.assistant.memory.domain.type;

/** Memory 한 줄의 종류다(ADR-052). */
public enum MemoryEntryType {
    /** 사실 하나. 지금까지의 Memory 가 모두 이것이다. */
    MEMORY,
    /** {@code document_key} 로 가리키는 긴 글 하나. 판을 쌓아 가며 고친다. */
    DOCUMENT,
    /** 다른 항목의 출처로 남긴 원문. 색인에 싣지 않는다. */
    SOURCE
}
