package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.application.model.FeedbackLabel;
import com.bifos.assistant.feedback.application.model.SubjectLabel;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 제안 하나의 사건을 첫 반응으로 읽는다. 저장하지 않는 순수 함수다.
 *
 * <p>잘못된 학습을 막는 규칙을 여기 둔다. 무응답은 싫어함이 아니다. 거절과 숨기기는 그 제안 하나에 대한 일회성 반응이고 오래 가는 선호가
 * 아니다. 오래 가는 선호의 근거는 사용자가 받아들인 Memory 제안 하나뿐이다(ADR-012).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FeedbackLabeler {

    /** 반응 읽기 규칙의 버전이다. 규칙이 바뀌면 올린다. */
    public static final int VERSION = 1;

    private static final Set<FeedbackEventType> POSITIVE =
            Set.of(FeedbackEventType.ACCEPTED, FeedbackEventType.APPROVED);
    private static final Set<FeedbackEventType> NEGATIVE =
            Set.of(FeedbackEventType.REJECTED, FeedbackEventType.DISMISSED);
    private static final Set<FeedbackEventType> OUTCOMES =
            Set.of(FeedbackEventType.EXECUTION_SUCCEEDED, FeedbackEventType.EXECUTION_FAILED);

    /** @param events 같은 제안의 사건. 순서는 상관없다 */
    public static SubjectLabel label(FeedbackSubjectType subject, List<FeedbackEvent> events) {
        List<FeedbackEvent> ordered = events.stream()
                .sorted(Comparator.comparing(FeedbackEvent::occurredAt)
                        .thenComparing(FeedbackEvent::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        FeedbackEventType decisive = ordered.stream()
                .filter(event -> event.actor() == FeedbackActor.USER)
                .map(FeedbackEvent::eventType)
                .filter(type -> POSITIVE.contains(type) || NEGATIVE.contains(type))
                .findFirst()
                .orElse(null);
        boolean surfaced = ordered.stream().anyMatch(event -> event.eventType() == FeedbackEventType.SURFACED);
        boolean postponed = ordered.stream()
                .anyMatch(event ->
                        event.actor() == FeedbackActor.USER && event.eventType() == FeedbackEventType.POSTPONED);
        FeedbackLabel label;
        if (decisive != null) {
            label = POSITIVE.contains(decisive) ? FeedbackLabel.ACCEPTED : FeedbackLabel.DECLINED;
        } else if (postponed) {
            label = FeedbackLabel.DEFERRED;
        } else if (surfaced) {
            label = FeedbackLabel.NO_RESPONSE;
        } else {
            label = FeedbackLabel.NOT_SURFACED;
        }
        boolean edited = ordered.stream()
                .anyMatch(
                        event -> event.actor() == FeedbackActor.USER && event.eventType() == FeedbackEventType.EDITED);
        FeedbackEventType outcome = ordered.stream()
                .map(FeedbackEvent::eventType)
                .filter(OUTCOMES::contains)
                .reduce((first, second) -> second)
                .orElse(null);
        return new SubjectLabel(
                label,
                wantsNow(label),
                edited,
                outcome,
                subject == FeedbackSubjectType.MEMORY && label == FeedbackLabel.ACCEPTED);
    }

    /** 미루기는 「지금은 아니다」 라서 거짓이다. 반응이 없거나 보인 적이 없으면 표본이 아니다. */
    private static Boolean wantsNow(FeedbackLabel label) {
        return switch (label) {
            case ACCEPTED -> Boolean.TRUE;
            case DECLINED, DEFERRED -> Boolean.FALSE;
            case NO_RESPONSE, NOT_SURFACED -> null;
        };
    }
}
