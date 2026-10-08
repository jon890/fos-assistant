package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemorySensitivity;

/** 한 collection 에서 한 민감도의 실릴 수 있는 항목 수다. 항목의 제목과 번호는 담지 않는다. */
public record MemoryCollectionCount(String collection, MemorySensitivity sensitivity, long entries) {}
