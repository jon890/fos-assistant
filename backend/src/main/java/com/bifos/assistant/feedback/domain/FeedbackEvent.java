package com.bifos.assistant.feedback.domain;

import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 판단 피드백 기록의 사건 한 줄이다. 덧붙이기만 하고 고치지 않는다.
 *
 * <p>칸의 뜻은 {@code docs/backend/schema/feedback.md} 가 갖는다. 제목, 본문, 인자, 원문을 담는 칸이 없다. 연결은 번호와 열쇠, 판은
 * 해시와 판 번호로만 둔다.
 */
@Entity
@Table(name = "decision_feedback_event")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedbackEvent {

    /** 이 표의 계약 버전이다. 칸의 뜻이 바뀌면 올린다. */
    public static final int CONTRACT_VERSION = 1;

    static final int SUBJECT_KEY_MAX_LENGTH = 80;
    static final int SHORT_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 24, updatable = false)
    private FeedbackSubjectType subjectType;

    @Column(name = "subject_key", nullable = false, length = SUBJECT_KEY_MAX_LENGTH, updatable = false)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24, updatable = false)
    private FeedbackEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 16, updatable = false)
    private FeedbackActor actor;

    @Column(name = "conversation_id", updatable = false)
    private Long conversationId;

    /** 제안을 낸 실행 트리의 루트다. 살펴보기 트리면 그 살펴보기의 {@code root_execution_id} 와 같다. */
    @Column(name = "origin_execution_id", updatable = false)
    private Long originExecutionId;

    @Column(name = "source_check_id", updatable = false)
    private Long sourceCheckId;

    @Column(name = "autonomy_decision_id", updatable = false)
    private Long autonomyDecisionId;

    @Column(name = "subject_version", length = SHORT_MAX_LENGTH, updatable = false)
    private String subjectVersion;

    @Column(name = "reason_code", length = SHORT_MAX_LENGTH, updatable = false)
    private String reasonCode;

    /** {@code EDITED} 에서 바뀐 칸 이름을 쉼표로 이은 것이다. 값은 담지 않는다. */
    @Column(name = "changed_fields", length = SHORT_MAX_LENGTH, updatable = false)
    private String changedFields;

    @Column(name = "contract_version", nullable = false, updatable = false)
    private int contractVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static FeedbackEvent of(
            Long userId,
            FeedbackSubjectType subjectType,
            String subjectKey,
            FeedbackEventType eventType,
            FeedbackActor actor,
            Long conversationId,
            Long originExecutionId,
            Long sourceCheckId,
            Long autonomyDecisionId,
            String subjectVersion,
            String reasonCode,
            List<String> changedFields,
            Instant occurredAt) {
        FeedbackEvent event = new FeedbackEvent();
        event.userId = userId;
        event.subjectType = subjectType;
        event.subjectKey = clip(subjectKey, SUBJECT_KEY_MAX_LENGTH);
        event.eventType = eventType;
        event.actor = actor;
        event.conversationId = conversationId;
        event.originExecutionId = originExecutionId;
        event.sourceCheckId = sourceCheckId;
        event.autonomyDecisionId = autonomyDecisionId;
        event.subjectVersion = clip(subjectVersion, SHORT_MAX_LENGTH);
        event.reasonCode = clip(reasonCode, SHORT_MAX_LENGTH);
        event.changedFields = changedFields == null || changedFields.isEmpty()
                ? null
                : clip(String.join(",", changedFields), SHORT_MAX_LENGTH);
        event.contractVersion = CONTRACT_VERSION;
        // 칸이 DATETIME(6) 이라 마이크로초까지만 둔다. 메모리의 값과 DB 에서 다시 읽은 값이 같아야 한다.
        event.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        return event;
    }

    /** 바뀐 칸 이름의 목록이다. 없으면 빈 목록이다. */
    public List<String> changedFieldList() {
        return changedFields == null ? List.of() : Arrays.asList(changedFields.split(","));
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
