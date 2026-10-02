package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 서비스 토큰과 그 토큰이 받는 Memory collection 의 key 다. */
@Embeddable
public record ServiceTokenCollectionId(
        @Column(name = "token_id", nullable = false) Long tokenId,

        @Column(name = "collection", nullable = false, length = 64)
        String collection)
        implements Serializable {}
