package com.bifos.assistant.memory.domain.type;

/** 에이전트가 이 항목에 닿는 방식이다(ADR-051). */
public enum MemoryRetrieval {
    /** 본문을 매 실행에 싣는다. */
    ALWAYS,
    /** 제목과 번호만 색인에 싣고 본문은 {@code memory_read} 로 읽는다. */
    SEARCH,
    /** 색인에도 싣지 않는다. 사람이 화면에서 찾아 볼 때만 읽는다. */
    ARCHIVE;

    /** 옛 {@code always_inject} 칸의 값을 옮긴다. */
    public static MemoryRetrieval ofAlwaysInject(boolean alwaysInject) {
        return alwaysInject ? ALWAYS : SEARCH;
    }
}
