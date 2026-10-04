package com.bifos.assistant.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 실행 하나에 실은 문맥 항목의 참조 한 줄이다. 어느 답에 어느 기록이 들어갔는지 나중에 찾으려고 남긴다(ADR-071).
 *
 * <p>제목과 본문을 담는 칸을 두지 않는다. {@code sourceRef} 로 원래 기록을 다시 찾는다. {@code source}, {@code bodyMode},
 * {@code freshness} 는 문맥 묶음의 이름을 문자열로 옮긴 값이다. {@code usage} 는 {@code context} 를 쓰지 못한다(ADR-068).
 *
 * <p>문자열 칸의 길이를 마이그레이션과 글자까지 맞춘다. 테스트는 이 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를
 * 검증한다.
 */
@Entity
@Table(name = "execution_context_source")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionContextSource {

    @EmbeddedId
    private ExecutionContextSourceId id;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** 원래 기록의 참조다. Memory 는 {@code memory:<번호>} 다. */
    @Column(name = "source_ref", nullable = false, length = 80)
    private String sourceRef;

    @Column(name = "body_mode", nullable = false, length = 16)
    private String bodyMode;

    @Column(name = "freshness", nullable = false, length = 16)
    private String freshness;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private ExecutionContextSource(
            Long executionId,
            int position,
            String source,
            String sourceRef,
            String bodyMode,
            String freshness,
            Instant createdAt) {
        this.id = new ExecutionContextSourceId(executionId, position);
        this.source = source;
        this.sourceRef = sourceRef;
        this.bodyMode = bodyMode;
        this.freshness = freshness;
        this.createdAt = createdAt;
    }

    public static ExecutionContextSource of(
            Long executionId,
            int position,
            String source,
            String sourceRef,
            String bodyMode,
            String freshness,
            Instant createdAt) {
        return new ExecutionContextSource(executionId, position, source, sourceRef, bodyMode, freshness, createdAt);
    }

    public Long executionId() {
        return id.executionId();
    }

    public int position() {
        return id.position();
    }
}
