package com.bifos.assistant.followup.application;

import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.followup.application.dto.LegacySuggestion;
import com.bifos.assistant.followup.application.dto.SuggestedItem;
import com.bifos.assistant.followup.application.dto.SuggestionOutcome;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.followup.infra.FollowUpSuggestionBodies;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기의 결과 블록이 낸 제안 항목을 할 일의 {@code PROPOSED} 줄로 저장한다(ADR-20261101 / proactive-suggestion).
 *
 * <p>사용자와 대화는 실행 줄에서 정한다. 호출하는 쪽이 사용자를 넘기지 않으므로 요청 본문이 사용자를 정하지 못한다. 중복 열쇠는 실행 번호와
 * 항목 식별자(없으면 순번)의 SHA-256 이라, 같은 실행의 같은 결과를 다시 넘겨도 줄이 하나다. 모델이 정한 topic 값으로 날짜를 넘는 중복을
 * 막지 않는다. 같은 제목의 열린 줄이 있으면 유일 제약대로 새 줄을 만들지 않는다.
 *
 * <p>쓰는 메서드에 {@code @Transactional} 을 붙이지 않는다. 항목마다 트랜잭션을 따로 열어 한 항목의 실패가 나머지를 되돌리지 않게 하고,
 * 유일 제약에 걸린 예외는 트랜잭션 밖에서 잡아 다시 읽는다. 본문과 제목은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowUpSuggestions {

    /** 한 실행이 낼 수 있는 제안 줄의 상한이다. */
    static final int MAX_PER_EXECUTION = 5;

    /** 제안 본문(JSON 글)의 글자 수 상한이다. 모델이 낸 자유 출력을 구조 없이 담으므로 크기만 막는다. */
    static final int DETAIL_MAX = 4000;

    /** 항목 식별자의 글자 수 상한이다. 중복 열쇠는 이것의 해시라 칸 길이와 상관없지만 끝없이 길어지지 않게 한다. */
    static final int ITEM_KEY_MAX = 120;

    /** 받아들이지 않은 제안이 지금 화면에서 빠지기까지의 기간이다. */
    static final Duration TTL = Duration.ofDays(7);

    private final FollowUpRepository followUps;
    private final AgentExecutionRepository executions;
    private final FollowUpSuggestionBodies bodies;
    private final DecisionFeedbackRecorder feedback;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 제안 항목을 저장한다.
     *
     * @param executionId 제안을 낸 살펴보기 실행의 번호
     * @return 항목 순서대로의 결과
     */
    public List<SuggestionOutcome> propose(Long executionId, List<SuggestedItem> items) {
        AgentExecution execution = executions.findById(executionId).orElse(null);
        if (execution == null || execution.userId() == null || execution.conversationId() == null) {
            log.warn("follow-up suggestion without owner executionId={}", executionId);
            return items.stream().map(item -> SuggestionOutcome.INVALID).toList();
        }
        List<SuggestionOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            outcomes.add(proposeOne(execution, index, items.get(index)));
        }
        log.info("follow-up suggestions userId={} executionId={} outcomes={}", execution.userId(), executionId, outcomes);
        return outcomes;
    }


    private SuggestionOutcome proposeOne(AgentExecution execution, int index, SuggestedItem item) {
        String title = item.title() == null ? "" : item.title().strip();
        int length = title.codePointCount(0, title.length());
        boolean detailTooLong = item.detailJson() != null && item.detailJson().length() > DETAIL_MAX;
        if (length < 1 || length > FollowUpService.TITLE_MAX || detailTooLong) {
            return SuggestionOutcome.INVALID;
        }
        String itemKey = item.itemKey() == null || item.itemKey().isBlank()
            ? Integer.toString(index)
            : item.itemKey().strip();
        itemKey = itemKey.substring(0, Math.min(itemKey.length(), ITEM_KEY_MAX));
        String body = item.detailJson() == null || item.detailJson().isBlank() ? null : item.detailJson();
        Draft draft = new Draft(
            execution.userId(),
            execution.conversationId(),
            execution.id(),
            title,
            FollowUpService.titleKey(title),
            Sha256.hex(execution.id() + ":" + itemKey),
            body);
        try {
            return transactions.execute(status -> insert(draft));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
            // 같은 항목이 동시에 들어와 유일 제약(중복 열쇠나 열린 제목)에 걸렸다. 먼저 저장된 줄이 있을 때만 같은 제안으로 답한다.
            if (!Boolean.TRUE.equals(transactions.execute(status -> duplicateOf(draft)))) {
                throw ex;
            }
            return SuggestionOutcome.DUPLICATE;
        }
    }


    /**
     * 옛 발견이나 문제 후보 하나를 제안 줄로 옮긴다. 이미 옮긴 줄이면 새 줄을 만들지 않는다.
     *
     * <p>옛 줄이 생긴 시각부터 {@link #TTL} 이 지났으면 만료된 제안으로 옮긴다. 화면에는 보이지 않고 열린 줄에서도 빠져, 같은 제목의 새
     * 제안을 막지 않는다. 그 밖에는 같은 제목의 열린 줄이 있으면 새 줄을 만들지 않고, 같은 대화에서 30일 안에 거절한 제목도 옮기지
     * 않는다. 한 실행의 제안 상한({@link #MAX_PER_EXECUTION})은 걸지 않는다. 옛 경로는 한 번에 그보다 많이 낼 수 있었다.
     *
     * @return {@code CREATED} 새 줄, {@code DUPLICATE} 이미 옮겼거나 같은 제목의 열린 줄이 있음, {@code DECLINED_BEFORE} 거절한 제목,
     * {@code INVALID} 제목이 비었음
     */
    public SuggestionOutcome migrateLegacy(LegacySuggestion item) {
        String title = item.title() == null ? "" : item.title().strip();
        if (title.codePointCount(0, title.length()) > FollowUpService.TITLE_MAX) {
            title = title.substring(0, title.offsetByCodePoints(0, FollowUpService.TITLE_MAX)).strip();
        }
        if (title.isEmpty()) {
            return SuggestionOutcome.INVALID;
        }
        Draft draft = new Draft(
            item.userId(),
            item.conversationId(),
            item.executionId(),
            title,
            FollowUpService.titleKey(title),
            item.dedupeKey(),
            item.detailJson() == null || item.detailJson().isBlank() ? null : item.detailJson());
        try {
            return transactions.execute(status -> insertLegacy(draft, item.createdAt()));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
            if (!Boolean.TRUE.equals(transactions.execute(status -> duplicateOf(draft)))) {
                throw ex;
            }
            return SuggestionOutcome.DUPLICATE;
        }
    }


    /** 그 사용자의 옮긴 제안 줄의 공개 식별자를 중복 열쇠로 찾는다. 없는 열쇠는 맵에 없다. */
    public Map<String, UUID> publicIdsOf(Long userId, Collection<String> dedupeKeys) {
        Map<String, UUID> found = new HashMap<>();
        if (dedupeKeys.isEmpty()) {
            return found;
        }
        for (FollowUp row : followUps.findByUserIdAndDedupeKeyIn(userId, dedupeKeys)) {
            found.put(row.dedupeKey(), row.publicId());
        }
        return found;
    }


    private SuggestionOutcome insertLegacy(Draft draft, Instant createdAt) {
        Instant now = clock.instant();
        if (followUps.findByUserIdAndDedupeKey(draft.userId(), draft.dedupeKey()).isPresent()) {
            return SuggestionOutcome.DUPLICATE;
        }
        Instant expiresAt = createdAt.plus(TTL);
        boolean expired = !expiresAt.isAfter(now);
        if (!expired) {
            FollowUp sameTitle = followUps
                .findByUserIdAndTitleKeyAndOpenMarker(draft.userId(), draft.titleKey(), FollowUp.OPEN_MARKER)
                .orElse(null);
            if (sameTitle != null) {
                if (!sameTitle.expiredAt(now)) {
                    return SuggestionOutcome.DUPLICATE;
                }
                sameTitle.releaseExpired(now);
                followUps.saveAndFlush(sameTitle);
            }
            if (followUps.existsByConversationIdAndTitleKeyAndStatusAndClosedAtAfter(
                draft.conversationId(),
                draft.titleKey(),
                FollowUpStatus.REJECTED,
                now.minus(FollowUpService.REJECTED_COOLDOWN))) {
                return SuggestionOutcome.DECLINED_BEFORE;
            }
        }
        SealedText sealed = draft.detailJson() == null
            ? null
            : bodies.seal(draft.userId(), draft.dedupeKey(), draft.detailJson());
        FollowUp row = FollowUp.suggested(
            draft.userId(),
            draft.conversationId(),
            draft.executionId(),
            draft.title(),
            draft.titleKey(),
            draft.dedupeKey(),
            sealed == null ? null : sealed.content(),
            sealed == null ? null : sealed.keyId(),
            expiresAt,
            createdAt);
        if (expired) {
            row.releaseExpired(now);
        }
        followUps.saveAndFlush(row);
        return SuggestionOutcome.CREATED;
    }


    /** 트랜잭션 안에서 제안 줄 하나를 저장한다. 억제 규칙에 걸리면 저장하지 않고 그 까닭을 돌려준다. */
    private SuggestionOutcome insert(Draft draft) {
        Instant now = clock.instant();
        if (followUps.findByUserIdAndDedupeKey(draft.userId(), draft.dedupeKey()).isPresent()) {
            return SuggestionOutcome.DUPLICATE;
        }
        FollowUp sameTitle = followUps
            .findByUserIdAndTitleKeyAndOpenMarker(draft.userId(), draft.titleKey(), FollowUp.OPEN_MARKER)
            .orElse(null);
        if (sameTitle != null) {
            if (!sameTitle.expiredAt(now)) {
                return SuggestionOutcome.DUPLICATE;
            }
            // 만료된 제안은 화면에서 이미 빠졌다. 같은 제목의 새 제안이 막히지 않게 열린 줄에서 뺀다.
            sameTitle.releaseExpired(now);
            followUps.saveAndFlush(sameTitle);
        }
        if (followUps.existsByConversationIdAndTitleKeyAndStatusAndClosedAtAfter(
            draft.conversationId(),
            draft.titleKey(),
            FollowUpStatus.REJECTED,
            now.minus(FollowUpService.REJECTED_COOLDOWN))) {
            return SuggestionOutcome.DECLINED_BEFORE;
        }
        if (followUps.countByProposedByExecutionId(draft.executionId()) >= MAX_PER_EXECUTION) {
            return SuggestionOutcome.TOO_MANY_IN_RUN;
        }
        SealedText sealed = draft.detailJson() == null
            ? null
            : bodies.seal(draft.userId(), draft.dedupeKey(), draft.detailJson());
        FollowUp saved = followUps.saveAndFlush(FollowUp.suggested(
            draft.userId(),
            draft.conversationId(),
            draft.executionId(),
            draft.title(),
            draft.titleKey(),
            draft.dedupeKey(),
            sealed == null ? null : sealed.content(),
            sealed == null ? null : sealed.keyId(),
            now.plus(TTL),
            now));
        feedback.record(FollowUpService.feedbackOf(saved, FeedbackEventType.SURFACED, FeedbackActor.AGENT, now));
        return SuggestionOutcome.CREATED;
    }


    /** 같은 실행의 같은 항목이거나 같은 제목의 열린 줄이 이미 있는가. */
    private boolean duplicateOf(Draft draft) {
        return followUps.findByUserIdAndDedupeKey(draft.userId(), draft.dedupeKey()).isPresent()
            || followUps
                .findByUserIdAndTitleKeyAndOpenMarker(draft.userId(), draft.titleKey(), FollowUp.OPEN_MARKER)
                .isPresent();
    }


    /** 검사를 마치고 열쇠를 계산한 제안 항목 하나다. */
    private record Draft(
        Long userId,
        Long conversationId,
        Long executionId,
        String title,
        String titleKey,
        String dedupeKey,
        String detailJson) {
    }
}
