package com.bifos.assistant.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 행의 저장 생성 시각만 기록한다. 업무 발생 시각과 식별자는 하위 엔티티가 소유한다. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Accessors(fluent = true)
public abstract class CreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
