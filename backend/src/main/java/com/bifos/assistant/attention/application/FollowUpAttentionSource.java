package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionFollowUpRef;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ConversationResultDeliveries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 {@code PROPOSED} 와 {@code OPEN} 할 일을 나를 기다리는 카드의 후보로 낸다(ADR-073).
 *
 * <p>제안은 늘 {@code LATER} 다. 에이전트가 짐작한 것이라 건수에 들지 않는다. 받아들인 할 일은 기한이 다가오거나 지났을 때, 연결한
 * 대화에 받아들인 뒤의 결과가 전해졌을 때 {@code NOW} 가 된다. 결과 도착은 맡긴 일과 승인 줄의 {@code result_delivered_at} 으로
 * 판정한다. {@code SYSTEM} 메시지는 자동 turn 한도 안내와 승인 거절 알림도 섞여 구분하지 못하므로 보지 않는다. 사용자가 그 대화에서
 * 주고받는 메시지도 세지 않는다.
 *
 * <p>숨기기, 미루기, 중복 억제는 {@link AttentionJudge} 가 한다. 여기서는 후보와 신호만 낸다. 할 일은 고치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class FollowUpAttentionSource implements AttentionCandidates {

    private static final String SOURCE = "FOLLOW_UP";

    private final FollowUpService followUps;
    private final ConversationResultDeliveries executionDeliveries;
    private final ConnectorActionService actionDeliveries;
    private final AttentionProperties properties;

    /** 기한이 지금 어느 구간에 있는가. {@code stateKey} 재료라서 구간이 바뀌면 숨긴 할 일이 다시 보인다. */
    private enum DueBand {
        NONE,
        DUE_SOON,
        OVERDUE
    }

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<FollowUpSnapshot> rows = followUps.openAndProposedOf(user.id());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Instant> lastDelivered = lastDeliveredAt(rows);
        return rows.stream().map(row -> candidate(row, lastDelivered, now)).toList();
    }

    /** 지운 대화는 빼고 읽는다. 지운 대화의 결과로 할 일을 올리지 않는다. 두 출처 가운데 늦은 시각을 대화의 값으로 쓴다. */
    private Map<Long, Instant> lastDeliveredAt(List<FollowUpSnapshot> rows) {
        Set<Long> linked = rows.stream()
                .filter(row -> row.conversationPublicId() != null)
                .map(FollowUpSnapshot::conversationId)
                .collect(Collectors.toSet());
        Map<Long, Instant> merged = new HashMap<>(executionDeliveries.lastDeliveredAt(linked));
        actionDeliveries
                .lastResultDeliveredAt(linked)
                .forEach((id, at) -> merged.merge(id, at, (a, b) -> a.isAfter(b) ? a : b));
        return merged;
    }

    private AttentionCandidate candidate(FollowUpSnapshot row, Map<Long, Instant> lastDelivered, Instant now) {
        boolean proposed = row.status() == FollowUpStatus.PROPOSED;
        String itemKey = "follow_up:" + row.publicId();
        List<AttentionSourceRef> sources = List.of(new AttentionSourceRef(SOURCE, itemKey, row.updatedAt()));
        AttentionFollowUpRef ref = new AttentionFollowUpRef(row.publicId(), row.dueAt(), row.waiting(), proposed);
        if (proposed) {
            return new AttentionCandidate(
                    CardKey.NEEDS_ME,
                    itemKey,
                    AttentionCandidates.stateKey(
                            AttentionTrigger.FOLLOW_UP_PROPOSED, row.updatedAt().toString()),
                    AttentionTrigger.FOLLOW_UP_PROPOSED,
                    false,
                    false,
                    row.waiting() ? List.of(AttentionSignal.WAITING) : List.of(),
                    AttentionConfidence.MODEL_INFERRED,
                    row.title(),
                    row.conversationPublicId(),
                    null,
                    row.createdAt(),
                    sources,
                    null,
                    null,
                    ref);
        }
        DueBand band = dueBand(row.dueAt(), now);
        Instant delivered = row.conversationPublicId() == null ? null : lastDelivered.get(row.conversationId());
        boolean linkedUpdate = delivered != null && delivered.isAfter(row.acceptedAt());
        List<AttentionSignal> signals = new ArrayList<>();
        if (band == DueBand.OVERDUE) {
            signals.add(AttentionSignal.OVERDUE);
        }
        if (band == DueBand.DUE_SOON) {
            signals.add(AttentionSignal.DUE_SOON);
        }
        if (linkedUpdate) {
            signals.add(AttentionSignal.LINKED_UPDATE);
        }
        boolean nowSignal = !signals.isEmpty();
        if (row.waiting()) {
            signals.add(AttentionSignal.WAITING);
        }
        String material = row.updatedAt() + "|" + band + "|" + (delivered == null ? "0" : delivered.toString());
        Instant at = linkedUpdate && delivered.isAfter(row.updatedAt()) ? delivered : row.updatedAt();
        return new AttentionCandidate(
                CardKey.NEEDS_ME,
                itemKey,
                AttentionCandidates.stateKey(AttentionTrigger.FOLLOW_UP_OPEN, material),
                AttentionTrigger.FOLLOW_UP_OPEN,
                false,
                nowSignal,
                List.copyOf(signals),
                AttentionConfidence.USER_CONFIRMED,
                row.title(),
                row.conversationPublicId(),
                null,
                at,
                sources,
                null,
                null,
                ref);
    }

    private DueBand dueBand(Instant dueAt, Instant now) {
        if (dueAt == null) {
            return DueBand.NONE;
        }
        if (dueAt.isBefore(now)) {
            return DueBand.OVERDUE;
        }
        return dueAt.isAfter(now.plus(properties.dueSoon())) ? DueBand.NONE : DueBand.DUE_SOON;
    }
}
