package com.bifos.assistant.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.LastModifiedDate;

/** 순수 저장 변경 시각이다. 기록을 시작하기 전의 과거 변경 시각은 NULL이다. */
@MappedSuperclass
@Getter
@Accessors(fluent = true)
public abstract class AuditedEntity extends CreatedAtEntity {

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
