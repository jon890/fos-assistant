package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 평가 시도 하나다. replay 는 기존 줄을 바꾸지 않고 같은 입력으로 새 줄을 만든다. */
@Entity
@Table(name = "proactive_value_evaluation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ValueEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_id", nullable = false, updatable = false)
    private Long checkId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "replay_of_id", updatable = false)
    private Long replayOfId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private DecisionOutcome outcome;

    @Convert(converter = DecisionEvidenceJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "JSON")
    private DecisionEvidence evidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ValueEvaluation of(
            Long checkId, Long userId, Long replayOfId, DecisionEvidence evidence, Instant now) {
        ValueEvaluation row = new ValueEvaluation();
        row.checkId = checkId;
        row.userId = userId;
        row.replayOfId = replayOfId;
        row.outcome = evidence.result().outcome();
        row.evidence = evidence;
        row.createdAt = now;
        return row;
    }

    public void finish(DecisionEvidence result) {
        if (outcome != DecisionOutcome.RUNNING) {
            throw new IllegalStateException("value evaluation has already ended");
        }
        evidence = result;
        outcome = result.result().outcome();
    }
}
