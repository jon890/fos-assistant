package com.bifos.assistant.memory.domain.type;

/** 묶음의 항목 하나를 대조한 결과다(ADR-058). */
public enum MemoryImportStatus {
    /** 저장할 수 있다. */
    NEW,
    /** 같은 출처가 이미 들어와 있다. */
    DUPLICATE,
    /** 출처는 다른데 같은 문서 이름이나 같은 제목이 이미 있다. */
    CONFLICT,
    /** 칸이 틀렸거나 지금 들일 수 없는 항목이다. */
    REJECTED
}
