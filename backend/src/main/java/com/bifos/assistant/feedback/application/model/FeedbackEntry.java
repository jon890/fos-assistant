package com.bifos.assistant.feedback.application.model;

import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 기록할 사건 하나다. 부르는 쪽이 아는 연결만 채운다. 실행 번호를 주면 기록기가 그 실행의 대화와 트리 루트를 채운다.
 *
 * <p>제목, 본문, 인자, 원문은 받지 않는다. 바뀐 것은 칸 이름({@code changedFields})으로만, 판은 해시나 판 번호({@code
 * subjectVersion})로만 받는다.
 *
 * @param subjectVersion 그때 제안의 판. 할 일은 {@code title_key}, Memory 는 판 번호, 승인 줄은 인자 해시다
 * @param reasonCode 사건의 정형 까닭. 어느 단추였는지나 실패 코드다
 * @param changedFields {@code EDITED} 에서 바뀐 칸 이름
 */
public record FeedbackEntry(
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

    public FeedbackEntry {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }

    public static FeedbackEntry of(
            Long userId,
            FeedbackSubjectType subjectType,
            Object subjectId,
            FeedbackEventType eventType,
            FeedbackActor actor,
            Instant occurredAt) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectType.key(subjectId),
                eventType,
                actor,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                occurredAt);
    }

    /** 지금 화면의 {@code itemKey} 를 그대로 열쇠로 쓴다. 종류는 열쇠의 머리로 정한다. */
    public static FeedbackEntry ofKey(
            Long userId,
            FeedbackSubjectType subjectType,
            String subjectKey,
            FeedbackEventType eventType,
            FeedbackActor actor,
            Instant occurredAt) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                occurredAt);
    }

    public FeedbackEntry conversation(Long id) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                id,
                originExecutionId,
                sourceCheckId,
                autonomyDecisionId,
                subjectVersion,
                reasonCode,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry originExecution(Long id) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                id,
                sourceCheckId,
                autonomyDecisionId,
                subjectVersion,
                reasonCode,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry sourceCheck(Long id) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                originExecutionId,
                id,
                autonomyDecisionId,
                subjectVersion,
                reasonCode,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry autonomyDecision(Long id) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                originExecutionId,
                sourceCheckId,
                id,
                subjectVersion,
                reasonCode,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry version(String version) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                originExecutionId,
                sourceCheckId,
                autonomyDecisionId,
                version,
                reasonCode,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry reason(String code) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                originExecutionId,
                sourceCheckId,
                autonomyDecisionId,
                subjectVersion,
                code,
                changedFields,
                occurredAt);
    }

    public FeedbackEntry changed(List<String> fields) {
        return new FeedbackEntry(
                userId,
                subjectType,
                subjectKey,
                eventType,
                actor,
                conversationId,
                originExecutionId,
                sourceCheckId,
                autonomyDecisionId,
                subjectVersion,
                reasonCode,
                fields,
                occurredAt);
    }
}
