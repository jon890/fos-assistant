package com.bifos.assistant.usage.application.model;

/** 실행이 Memory 항목의 본문을 받은 길이다(ADR-20261008 / memory-facts). 응답에는 이름 그대로 싣는다. */
public enum MemoryUseVia {
    /** 항상 층에 본문까지 실었다. */
    ALWAYS,
    /** 개인 사실 구역에 본문까지 실었다. */
    FACTS,
    /** {@code memory_read} 로 본문을 읽었다. */
    READ
}
