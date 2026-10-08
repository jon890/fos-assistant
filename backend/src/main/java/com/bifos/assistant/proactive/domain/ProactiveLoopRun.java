package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.domain.type.LoopSkippedReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 매일 깨우기 살펴보기 하나를 잇는 시도다(ADR-20261008 / daily-loop). 원천 살펴보기마다 하나이고 다시 부르지 않는다.
 *
 * <p>글과 원문, provider 이름은 두지 않는다. provider 는 평가의 근거 JSON 이 갖는다.
 */
@Entity
@Table(
        name = "proactive_loop_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_proactive_loop_run_source", columnNames = "source_check_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ProactiveLoopRun {

    static final int ERROR_CODE_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "source_check_id", nullable = false, updatable = false)
    private Long sourceCheckId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LoopRunStatus status;

    /** {@code SKIPPED} 의 까닭이다. 아니면 비어 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "skipped_reason", length = 32, updatable = false)
    private LoopSkippedReason skippedReason;

    /** {@code FAILED} 의 까닭이다. 거절한 오류 코드, {@code INTERNAL_ERROR}, 기동 때 닫은 {@code INTERRUPTED} 가운데 하나다. */
    @Column(name = "error_code", length = ERROR_CODE_MAX_LENGTH)
    private String errorCode;

    /** 이 시도가 만든 평가다. 평가 줄이 없거나 지워졌으면 비어 있다. */
    @Column(name = "evaluation_id")
    private Long evaluationId;

    /** 시도를 저장한 시각이다. 하루 상한을 셀 때 쓴다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 평가와 판정을 부르기 전에 저장하는 시도다. */
    public static ProactiveLoopRun running(Long userId, Long sourceCheckId, Instant now) {
        ProactiveLoopRun row = new ProactiveLoopRun();
        row.userId = userId;
        row.sourceCheckId = sourceCheckId;
        row.status = LoopRunStatus.RUNNING;
        row.createdAt = now;
        return row;
    }

    /** 평가하지 않고 건너뛴 시도다. 저장하는 순간 끝난다. */
    public static ProactiveLoopRun skipped(Long userId, Long sourceCheckId, LoopSkippedReason reason, Instant now) {
        ProactiveLoopRun row = new ProactiveLoopRun();
        row.userId = userId;
        row.sourceCheckId = sourceCheckId;
        row.status = LoopRunStatus.SKIPPED;
        row.skippedReason = reason;
        row.createdAt = now;
        row.finishedAt = now;
        return row;
    }

    public void decided(Long evaluationId, Instant now) {
        this.status = LoopRunStatus.DECIDED;
        this.evaluationId = evaluationId;
        this.finishedAt = now;
    }

    public void failed(String errorCode, Long evaluationId, Instant now) {
        this.status = LoopRunStatus.FAILED;
        this.errorCode = errorCode;
        this.evaluationId = evaluationId;
        this.finishedAt = now;
    }
}
