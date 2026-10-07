package com.bifos.assistant.proactive.domain;

import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
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
 * 살펴보기 결과 버전 3의 문제 후보 하나다(ADR-092).
 *
 * <p>받아들인 것과 버린 것을 모두 남긴다. 다음 살펴보기의 중복 판정과 입력에 쓰고, 우선순위를 정하는 다음 단계가 읽는다. 글은 모델이 쓴
 * 것이고 대화에 그리지 않는다. 근거는 발견의 참조만 두고 원문 본문을 두지 않는다.
 */
@Entity
@Table(name = "proactive_check_problem")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
public class ProactiveCheckProblem {

    static final int PROBLEM_KEY_MAX_LENGTH = 120;
    static final int PROBLEM_MAX_LENGTH = 300;
    static final int RELATED_GOAL_MAX_LENGTH = 200;
    static final int SHORT_MAX_LENGTH = 16;
    static final int ACTION_TEXT_MAX_LENGTH = 200;
    static final int EXPECTED_BENEFIT_MAX_LENGTH = 300;
    static final int RISK_MAX_LENGTH = 200;
    static final int CHANGE_SINCE_LAST_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_id", nullable = false)
    private Long checkId;

    /** 점검 대화. {@code proactive_check.conversation_id} 와 같다. 중복 판정을 대화로 읽으려고 둔다. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProblemStatus status;

    /** {@code DROPPED} 의 까닭. */
    @Enumerated(EnumType.STRING)
    @Column(name = "drop_reason", length = 32)
    private ProblemDropReason dropReason;

    /** 앞뒤 공백을 지우고 소문자로 맞춘 문제 키. 비었으면 빈 글이다. */
    @Column(name = "problem_key", nullable = false, length = PROBLEM_KEY_MAX_LENGTH)
    private String problemKey;

    @Column(name = "problem", length = PROBLEM_MAX_LENGTH)
    private String problem;

    @Column(name = "related_goal", length = RELATED_GOAL_MAX_LENGTH)
    private String relatedGoal;

    @Column(name = "action_type", length = SHORT_MAX_LENGTH)
    private String actionType;

    @Column(name = "action_text", length = ACTION_TEXT_MAX_LENGTH)
    private String actionText;

    @Column(name = "confidence", length = SHORT_MAX_LENGTH)
    private String confidence;

    @Column(name = "expected_benefit", length = EXPECTED_BENEFIT_MAX_LENGTH)
    private String expectedBenefit;

    @Column(name = "side_effect", length = SHORT_MAX_LENGTH)
    private String sideEffect;

    @Column(name = "risk", length = RISK_MAX_LENGTH)
    private String risk;

    @Column(name = "change_since_last", length = CHANGE_SINCE_LAST_MAX_LENGTH)
    private String changeSinceLast;

    /** 근거로 받아들인 발견의 참조. 근거가 없으면 빈 목록이다. */
    @Convert(converter = ProblemEvidenceJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", nullable = false, columnDefinition = "JSON")
    private List<ProblemEvidence> evidence;

    /** 근거 발견의 확인 시각 가운데 가장 이른 것. 근거가 없으면 비어 있다. */
    @Column(name = "evidence_checked_at")
    private Instant evidenceCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 검사한 후보 하나를 줄로 만든다. 칸 길이를 넘는 글은 잘라 넣는다. 모델이 쓴 글이라 길이를 믿지 않는다. */
    public static ProactiveCheckProblem of(
            Long checkId,
            Long conversationId,
            ProblemStatus status,
            ProblemDropReason dropReason,
            String problemKey,
            String problem,
            String relatedGoal,
            String actionType,
            String actionText,
            String confidence,
            String expectedBenefit,
            String sideEffect,
            String risk,
            String changeSinceLast,
            List<ProblemEvidence> evidence,
            Instant evidenceCheckedAt,
            Instant now) {
        ProactiveCheckProblem row = new ProactiveCheckProblem();
        row.checkId = checkId;
        row.conversationId = conversationId;
        row.status = status;
        row.dropReason = dropReason;
        row.problemKey = problemKey == null ? "" : truncate(problemKey, PROBLEM_KEY_MAX_LENGTH);
        row.problem = truncate(problem, PROBLEM_MAX_LENGTH);
        row.relatedGoal = truncate(relatedGoal, RELATED_GOAL_MAX_LENGTH);
        row.actionType = truncate(actionType, SHORT_MAX_LENGTH);
        row.actionText = truncate(actionText, ACTION_TEXT_MAX_LENGTH);
        row.confidence = truncate(confidence, SHORT_MAX_LENGTH);
        row.expectedBenefit = truncate(expectedBenefit, EXPECTED_BENEFIT_MAX_LENGTH);
        row.sideEffect = truncate(sideEffect, SHORT_MAX_LENGTH);
        row.risk = truncate(risk, RISK_MAX_LENGTH);
        row.changeSinceLast = truncate(changeSinceLast, CHANGE_SINCE_LAST_MAX_LENGTH);
        row.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        row.evidenceCheckedAt = evidenceCheckedAt;
        row.createdAt = now;
        return row;
    }

    /** 칸 길이까지만 남긴다. 상한 자리에서 대리 쌍이 나뉘면 그 앞에서 자른다. */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength);
    }
}
