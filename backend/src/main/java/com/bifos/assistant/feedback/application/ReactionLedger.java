package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.application.dto.ControlEntry;
import com.bifos.assistant.feedback.application.dto.ReactionRecord;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지금 화면의 숨김, 미루기, 복원과 노출, 열기 사건을 반응 기록({@code decision_feedback_event})에 남기고 읽는다(ADR-20261101 /
 * proactive-suggestion 의 순서 5).
 *
 * <p>{@link DecisionFeedbackRecorder} 와 달리 부르는 쪽의 트랜잭션 안에서 바로 저장하고 실패를 삼키지 않는다. 숨김과 미루기의 현재값을
 * 이 기록의 마지막 제어 사건에서 구하므로, 기록하지 못했으면 부르는 쪽의 동작도 실패해야 한다. 같은 {@code event_key} 가 이미 있으면
 * 넣지 않고 {@code false} 를 낸다. 동시에 같은 열쇠가 들어오면 유일 제약이 한쪽을 거절해 예외가 부르는 쪽으로 간다.
 */
@Component
@RequiredArgsConstructor
public class ReactionLedger {

    /** 마지막 제어 사건을 가리는 사건 종류다. 숨김과 미루기를 건 뒤 복원이 풀 수 있다. */
    private static final List<FeedbackEventType> CONTROL_TYPES = List.of(FeedbackEventType.DISMISSED, FeedbackEventType.POSTPONED, FeedbackEventType.RESTORED);

    private static final List<FeedbackEventType> VIEW_TYPES = List.of(FeedbackEventType.SHOWN, FeedbackEventType.OPENED, FeedbackEventType.ACTED);

    private static final List<FeedbackEventType> METRIC_CONTROLS = List.of(FeedbackEventType.DISMISSED, FeedbackEventType.POSTPONED);

    private final FeedbackEventRepository events;
    private final FeedbackEventWriter writer;


    /**
     * 사건 한 줄을 부르는 쪽 트랜잭션 안에서 넣는다.
     *
     * @return 넣었으면 true, 같은 열쇠가 이미 있어 넘어갔으면 false
     */
    @Transactional
    public boolean append(ControlEntry entry) {
        if (entry.eventKey() != null && events.existsByUserIdAndEventKey(entry.entry().userId(), entry.eventKey())) {
            return false;
        }
        events.saveAndFlush(writer.toEvent(entry.entry(), true).control(entry.cardKey(), entry.untilAt(), entry.eventKey()));
        return true;
    }


    /** 그 사용자의 카드마다 항목마다 마지막 제어 사건이다. 복원이 마지막이면 그 항목은 제어가 없지만 복원 줄로 함께 나온다. */
    @Transactional(readOnly = true)
    public List<ReactionRecord> currentControls(Long userId) {
        return events.findCurrentControls(userId, CONTROL_TYPES).stream().map(ReactionLedger::record).toList();
    }


    /** 그 카드의 그 항목의 마지막 제어 사건이다. 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<ReactionRecord> latestControl(Long userId, String cardKey, String subjectKey) {
        return events
            .findFirstByUserIdAndCardKeyAndSubjectKeyAndEventTypeInOrderByIdDesc(
                userId, cardKey, subjectKey, CONTROL_TYPES)
            .map(ReactionLedger::record);
    }


    /** 그 항목의 그 상태에서 가장 나중에 남긴 노출 사건이다. 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<ReactionRecord> lastShown(Long userId, String itemKey, String stateKey) {
        return events
            .findFirstByUserIdAndSubjectKeyAndSubjectVersionAndEventTypeOrderByIdDesc(
                userId, itemKey, stateKey, FeedbackEventType.SHOWN)
            .map(ReactionLedger::record);
    }


    /** 그 사용자의 줄 가운데 {@code eventKeys} 에 이미 있는 열쇠다. */
    @Transactional(readOnly = true)
    public Set<String> existingKeys(Long userId, Collection<String> eventKeys) {
        return eventKeys.isEmpty() ? Set.of() : new HashSet<>(events.findEventKeys(userId, eventKeys));
    }


    /** {@code since} 뒤의 모든 사용자의 지금 화면 지표 사건이다. 노출, 열기, 동작과 카드가 있는 숨김, 미루기다. */
    @Transactional(readOnly = true)
    public List<ReactionRecord> attentionMetricEventsSince(Instant since, Collection<String> controlReasons) {
        return events.findAttentionMetricEvents(since, VIEW_TYPES, METRIC_CONTROLS, controlReasons).stream()
            .map(ReactionLedger::record)
            .toList();
    }


    /**
     * {@code before} 보다 먼저 남긴 노출, 열기, 동작 사건을 지운다. 제어 사건은 지우지 않는다.
     *
     * @return 지운 줄 수
     */
    @Transactional
    public int deleteAttentionViewsBefore(Instant before) {
        return events.deleteAttentionViewsBefore(VIEW_TYPES, before);
    }


    /** 보관 기간 정리가 쓰는 제어 사건 종류다. */
    static List<FeedbackEventType> controlTypes() {
        return CONTROL_TYPES;
    }


    private static ReactionRecord record(FeedbackEvent event) {
        return new ReactionRecord(
            event.id(),
            event.userId(),
            event.subjectKey(),
            event.subjectVersion(),
            event.eventType(),
            event.reasonCode(),
            event.cardKey(),
            event.untilAt(),
            event.occurredAt());
    }
}
