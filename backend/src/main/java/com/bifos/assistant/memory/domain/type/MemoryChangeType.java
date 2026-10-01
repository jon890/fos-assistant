package com.bifos.assistant.memory.domain.type;

/** 판 하나가 지금 값에서 물러난 까닭이다. */
public enum MemoryChangeType {
    /** 고쳐서 다음 판이 생겼다. */
    UPDATED,
    /** 지웠다. 이 판이 그 항목의 마지막 값이다. */
    DELETED
}
