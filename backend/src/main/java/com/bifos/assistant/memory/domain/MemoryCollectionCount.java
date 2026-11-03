package com.bifos.assistant.memory.domain;

/** 한 collection 의 실릴 수 있는 항목 수다. 항목의 제목과 번호는 담지 않는다. */
public record MemoryCollectionCount(String collection, long entries) {
}
