package com.bifos.assistant.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.data.domain.Persistable;

/**
 * 실행 하나에 실은 문맥 항목의 참조 한 줄이다. 어느 답에 어느 기록이 들어갔는지 나중에 찾으려고 남긴다(ADR-071).
 *
 * <p>제목과 본문을 담는 칸을 두지 않는다. {@code sourceRef} 로 원래 기록을 다시 찾는다. {@code source}, {@code bodyMode},
 * {@code freshness} 는 문맥 묶음의 이름을 문자열로 옮긴 값이다. {@code usage} 는 {@code context} 를 쓰지 못한다(ADR-068).
 *
 * <p>이 표는 넣기만 하고 고치거나 다시 저장하지 않는다. 그래서 늘 새 줄로 저장해(insert) 같은 위치에 두 번 쓰면 덮어쓰지 않고 기본 키
 * 위반으로 드러난다.
 *
 * <p>문자열 칸의 길이를 마이그레이션과 글자까지 맞춘다. 테스트는 이 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를
 * 검증한다.
 */
@Entity
@Table(name = "execution_context_source")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionContextSource implements Persistable<ExecutionContextSourceId> {

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

    /** 데이터베이스에 있는 줄이다. 읽어 왔거나 방금 넣었을 때 참이 된다. */
    @Transient
    private boolean persisted;

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

    /** Spring Data 의 {@link Persistable} 이 요구하는 이름이다. 값은 Lombok 이 만든 {@link #id()} 와 같다. */
    @Override
    public ExecutionContextSourceId getId() {
        return id();
    }

    /**
     * 줄은 늘 새로 넣는다. 번호를 직접 정하므로 이것이 없으면 저장이 같은 position 의 줄을 조용히 덮어쓴다.
     *
     * <p>같은 position 의 줄이 이미 있으면 기본 키 위반으로 실패한다. 덧붙이는 쪽이 그 실패를 보고 position 을 다시 정한다.
     */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
