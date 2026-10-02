package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.type.MemoryImportStatus;

/**
 * 항목 하나의 대조 결과다. {@code reason} 은 NEW 와 DUPLICATE 에서 null 이고, {@code memoryId} 는 저장한 때만 있다.
 */
public record MemoryImportOutcome(
        int index, String sourceRef, MemoryImportStatus status, String reason, Long memoryId) {}
