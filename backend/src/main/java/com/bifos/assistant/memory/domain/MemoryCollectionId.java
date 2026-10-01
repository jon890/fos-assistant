package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 그룹과 그 안의 collection key 다. */
@Embeddable
public record MemoryCollectionId(
        @Column(name = "group_id", nullable = false) Long groupId,

        @Column(name = "collection_key", nullable = false, length = 64)
        String key)
        implements Serializable {}
