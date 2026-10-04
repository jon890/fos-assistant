package com.bifos.assistant.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 실행과 그 실행의 문맥 안에서의 순서로 이루어진 key 다. 순서는 0 부터 센다. */
@Embeddable
public record ExecutionContextSourceId(
        @Column(name = "execution_id", nullable = false) Long executionId,

        @Column(name = "position", nullable = false) Integer position)
        implements Serializable {}
