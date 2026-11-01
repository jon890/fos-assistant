package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.application.dto.AttentionConfidence;
import com.bifos.assistant.attention.application.dto.AttentionFollowUpRef;
import com.bifos.assistant.attention.application.dto.AttentionSignal;
import com.bifos.assistant.attention.application.dto.AttentionSourceRef;
import com.bifos.assistant.attention.application.port.AttentionCandidates;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 제안, 곧 할 일의 {@code PROPOSED} 줄을 나를 기다리는 카드의 제안 카드 후보로 낸다(ADR-20261101 / proactive-suggestion).
 *
 * <p>먼저 살펴보기가 낸 제안과 대화 중 에이전트가 낸 할 일 제안이 모두 이 줄이다. 만료된 제안은 {@link FollowUpService#openAndProposedOf}
 * 가 이미 뺀다. 숨기기와 미루기는 반응 기록을 읽는 {@link AttentionJudge} 가 한다. 모델이 짐작한 것이라 늘 {@code LATER} 이고 건수에 들지
 * 않는다. 받아들이면 같은 줄이 {@code OPEN} 이 되어 {@link FollowUpAttentionSource} 가 낸다. 할 일을 고치거나 새로 만들지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SuggestionCandidates implements AttentionCandidates {

    private static final String SOURCE = "FOLLOW_UP";

    private final FollowUpService followUps;


    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }


    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        return followUps.openAndProposedOf(user.id()).stream()
            .filter(row -> row.status() == FollowUpStatus.PROPOSED)
            .map(SuggestionCandidates::candidate)
            .toList();
    }


    private static AttentionCandidate candidate(FollowUpSnapshot row) {
        String itemKey = "follow_up:" + row.publicId();
        return new AttentionCandidate(
            CardKey.NEEDS_ME,
            itemKey,
            AttentionCandidates.stateKey(AttentionTrigger.FOLLOW_UP_PROPOSED, row.updatedAt().toString()),
            AttentionTrigger.FOLLOW_UP_PROPOSED,
            false,
            false,
            row.waiting() ? List.of(AttentionSignal.WAITING) : List.of(),
            AttentionConfidence.MODEL_INFERRED,
            row.title(),
            row.conversationPublicId(),
            null,
            row.createdAt(),
            List.of(new AttentionSourceRef(SOURCE, itemKey, row.updatedAt())),
            null,
            null,
            new AttentionFollowUpRef(row.publicId(), row.dueAt(), row.waiting(), true, row.proposed()));
    }
}
