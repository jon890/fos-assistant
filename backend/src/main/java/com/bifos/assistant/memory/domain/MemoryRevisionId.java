package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 항목 번호와 판 번호다. 지운 항목의 판도 남으므로 항목 번호는 {@code memory} 에 없는 번호일 수 있다. */
@Embeddable
public record MemoryRevisionId(
        @Column(name = "memory_id", nullable = false) Long memoryId,
        @Column(name = "revision", nullable = false) int revision)
        implements Serializable {}
