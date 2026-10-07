package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 행동 정책의 판정 하나다(ADR-20261007 autonomy-policy). 후보 하나에 한 줄이고 같은 평가를 다시 판정하면 새 줄을 남긴다.
 *
 * <p>{@code EXECUTE} 만 실행 키를 가진다. 실행 키는 원천 살펴보기마다 유일해 같은 원천에서 자동 실행이 두 번 나가지 않는다.
 */
@Entity
@Table(name = "proactive_autonomy_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class AutonomyDecision {

    static final int EXECUTION_ERROR_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private Long evaluationId;

    /** 평가 스냅샷의 후보 식별자다. 지금의 후보 줄이 없어도 남는다. */
    @Column(name = "candidate_id", nullable = false, updatable = false)
    private Long candidateId;

    @Column(name = "source_check_id", nullable = false, updatable = false)
    private Long sourceCheckId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_level", nullable = false, length = 16, updatable = false)
    private AutonomyLevel level;

    @Convert(converter = AutonomyReasonsJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons_json", nullable = false, columnDefinition = "JSON", updatable = false)
    private List<AutonomyReason> reasons;

    @Convert(converter = AutonomyInputsJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs_json", nullable = false, columnDefinition = "JSON", updatable = false)
    private AutonomyInputs inputs;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "execution_key", length = 64, updatable = false)
    private String executionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 16)
    private AutonomyExecutionStatus executionStatus;

    @Column(name = "execution_check_id")
    private Long executionCheckId;

    @Column(name = "execution_error", length = EXECUTION_ERROR_MAX_LENGTH)
    private String executionError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code EXECUTE} 이면 원천 살펴보기의 실행 키와 {@code PENDING} 을 함께 둔다. */
    public static AutonomyDecision of(
            Long userId,
            Long evaluationId,
            Long candidateId,
            Long sourceCheckId,
            AutonomyLevel level,
            List<AutonomyReason> reasons,
            AutonomyInputs inputs,
            int policyVersion,
            Instant now) {
        AutonomyDecision row = new AutonomyDecision();
        row.userId = userId;
        row.evaluationId = evaluationId;
        row.candidateId = candidateId;
        row.sourceCheckId = sourceCheckId;
        row.level = level;
        row.reasons = List.copyOf(reasons);
        row.inputs = inputs;
        row.policyVersion = policyVersion;
        if (level == AutonomyLevel.EXECUTE) {
            row.executionKey = executionKey(sourceCheckId);
            row.executionStatus = AutonomyExecutionStatus.PENDING;
        }
        row.createdAt = now;
        return row;
    }

    /** 원천 살펴보기 하나에 자동 실행 하나를 허락하는 키다. */
    public static String executionKey(Long sourceCheckId) {
        return "check:" + sourceCheckId;
    }

    public void started(Long checkId) {
        requirePending();
        executionStatus = AutonomyExecutionStatus.STARTED;
        executionCheckId = checkId;
    }

    public void failed(String errorCode) {
        requirePending();
        executionStatus = AutonomyExecutionStatus.FAILED;
        executionError = errorCode == null || errorCode.length() <= EXECUTION_ERROR_MAX_LENGTH
                ? errorCode
                : errorCode.substring(0, EXECUTION_ERROR_MAX_LENGTH);
    }

    private void requirePending() {
        if (executionStatus != AutonomyExecutionStatus.PENDING) {
            throw new IllegalStateException("autonomous execution is not pending");
        }
    }
}
