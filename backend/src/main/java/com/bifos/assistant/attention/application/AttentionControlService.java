package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.application.dto.AttentionCard;
import com.bifos.assistant.attention.application.dto.AttentionItem;
import com.bifos.assistant.attention.application.dto.AttentionReaction;
import com.bifos.assistant.attention.application.dto.AttentionSnapshot;
import com.bifos.assistant.attention.config.AttentionProperties;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.feedback.application.dto.ControlEntry;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.application.dto.ReactionRecord;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지금 화면의 숨기기, 미루기, 되돌리기와 사용자가 항목에 한 일의 사건을 받는다. 계약은 {@code docs/features/attention.md} 의
 * 「API(먼저 알리기와 지금 화면의 판정)」 가 갖는다.
 *
 * <p>숨기기, 미루기, 되돌리기는 반응 기록({@code decision_feedback_event})에 제어 사건 하나를 필수로 남긴다. 현재 숨김과 미루기는 카드와
 * 항목마다 마지막 제어 사건에서 구한다. 사건을 남기지 못하면 그 요청도 실패한다.
 *
 * <p>메서드에 {@code @Transactional} 을 붙이지 않는다. 사건은 여기서 연 짧은 트랜잭션 안에서 저장하고, 같은 요청 키가 동시에 와 유일
 * 제약에 걸리면 그 트랜잭션 밖에서 먼저 들어간 사건을 확인한다. 메서드 전체를 한 트랜잭션으로 묶으면 예외를 잡아도 rollback-only 로
 * 남아 커밋할 때 실패한다.
 *
 * <p>숨기기와 미루기는 요청자의 지금 후보에 있는 항목만 받는다. 남의 항목과 없는 항목을 같은 응답으로 숨긴다.
 */
@Service
public class AttentionControlService {

    /** {@code decision_feedback_event.subject_key} 의 칸 길이다. */
    private static final int ITEM_KEY_MAX = 80;

    /** {@code decision_feedback_event.subject_version} 의 칸 길이다. */
    private static final int STATE_KEY_MAX = 64;

    /** 제어 요청 키의 최대 길이다. 열쇠는 해시해 칸 길이를 넘지 않으므로 입력만 막는다. */
    private static final int REQUEST_KEY_MAX = 100;

    private final AttentionService attention;
    private final ReactionLedger ledger;
    private final AttentionEventWriter writer;
    private final AttentionProperties properties;
    private final SuggestionFeedback suggestions;
    private final TransactionTemplate transactions;
    private final Clock clock;


    // 생성자를 직접 쓴다. 제어 사건을 저장하는 트랜잭션을 여기서 만든다.
    public AttentionControlService(
        AttentionService attention,
        ReactionLedger ledger,
        AttentionEventWriter writer,
        AttentionProperties properties,
        SuggestionFeedback suggestions,
        PlatformTransactionManager transactionManager,
        Clock clock) {
        this.attention = attention;
        this.ledger = ledger;
        this.writer = writer;
        this.properties = properties;
        this.suggestions = suggestions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }


    /** 요청 키 없이 숨긴다. 같은 상태로 이미 숨겼으면 사건을 더하지 않는다. */
    public void hide(CurrentUser user, CardKey card, String itemKey, String stateKey) {
        hide(user, card, itemKey, stateKey, null);
    }


    /**
     * 그 카드에서 클라이언트가 본 상태가 바뀔 때까지 숨긴다. 서버가 지금의 상태로 바꿔 넣지 않는다. 보고 누르는 사이에 상태가
     * 바뀌었으면 새 상태는 사용자가 보지 않은 것이라 다시 보여야 한다.
     *
     * @param card 모르는 카드 글이면 null
     * @param requestKey 재전송을 한 사건으로 합치는 열쇠. 없으면 null
     */
    public void hide(CurrentUser user, CardKey card, String itemKey, String stateKey, String requestKey) {
        requireItemKey(itemKey);
        requireStateKey(stateKey);
        requireCard(card);
        requireRequestKey(requestKey);
        AttentionSnapshot snapshot = attention.snapshot(user);
        AttentionCandidate candidate = requireCandidate(snapshot, card, itemKey);
        FeedbackEntry entry = suggestions.controlEntry(
            user, candidate, FeedbackEventType.DISMISSED, FeedbackLabeler.ATTENTION_HIDE, stateKey, snapshot.now());
        writeControl(
            user,
            card,
            itemKey,
            new ControlEntry(
                entry,
                card.name(),
                null,
                AttentionReactionCodec.controlEventKey(requestKey, FeedbackEventType.DISMISSED, card.name(), itemKey)),
            latest -> latest.filter(record -> record.type() == FeedbackEventType.DISMISSED)
                .filter(record -> Objects.equals(record.stateKey(), stateKey))
                .isPresent());
    }


    /** 요청 키 없이 미룬다. 같은 기한으로 이미 미뤘으면 사건을 더하지 않는다. */
    public void snooze(CurrentUser user, CardKey card, String itemKey, Instant until) {
        snooze(user, card, itemKey, until, null);
    }


    /**
     * 그 카드에서 {@code until} 까지 미룬다. {@code until} 은 지금보다 뒤이고 지금부터 {@code snooze-max} 안이어야 한다.
     *
     * @param card 모르는 카드 글이면 null
     * @param requestKey 재전송을 한 사건으로 합치는 열쇠. 없으면 null
     */
    public void snooze(CurrentUser user, CardKey card, String itemKey, Instant until, String requestKey) {
        requireItemKey(itemKey);
        requireCard(card);
        requireRequestKey(requestKey);
        Instant requestedAt = clock.instant();
        if (until == null || !until.isAfter(requestedAt) || until.isAfter(requestedAt.plus(properties.snoozeMax()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "until must be after now and within the snooze limit");
        }
        AttentionSnapshot snapshot = attention.snapshot(user);
        AttentionCandidate candidate = requireCandidate(snapshot, card, itemKey);
        // 미루기 요청에는 상태가 없어 그 카드 후보의 지금 상태를 사건에 남긴다. 지표가 같은 항목의 같은 상태로 묶는다.
        FeedbackEntry entry = suggestions.controlEntry(
            user,
            candidate,
            FeedbackEventType.POSTPONED,
            FeedbackLabeler.ATTENTION_SNOOZE,
            candidate.stateKey(),
            snapshot.now());
        Instant stored = until.truncatedTo(ChronoUnit.MICROS);
        writeControl(
            user,
            card,
            itemKey,
            new ControlEntry(
                entry,
                card.name(),
                until,
                AttentionReactionCodec.controlEventKey(requestKey, FeedbackEventType.POSTPONED, card.name(), itemKey)),
            latest -> latest.filter(record -> record.type() == FeedbackEventType.POSTPONED)
                .filter(record -> stored.equals(record.untilAt()))
                .isPresent());
    }


    /** 요청 키 없이 되돌린다. 마지막 제어가 이미 복원이면 사건을 더하지 않는다. */
    public void restore(CurrentUser user, CardKey card, String itemKey) {
        restore(user, card, itemKey, null);
    }


    /**
     * 그 카드의 그 항목의 숨기기와 미루기를 푼다. 풀 것이 없어도 끝난다. 푸는 일도 사건이라 이력은 남고, 마지막 제어가 이미 복원일 때만 사건을 더하지 않는다.
     *
     * @param card 모르는 카드 글이면 null
     * @param requestKey 재전송을 한 사건으로 합치는 열쇠. 없으면 null
     */
    public void restore(CurrentUser user, CardKey card, String itemKey, String requestKey) {
        requireItemKey(itemKey);
        requireCard(card);
        requireRequestKey(requestKey);
        // 되돌릴 때 그 항목이 후보에서 빠졌을 수 있어 원천을 읽지 않고, 제안이 아닌 항목의 사건으로 남긴다.
        FeedbackEntry entry = FeedbackEntry.ofKey(
            user.id(),
            FeedbackSubjectType.ATTENTION_ITEM,
            itemKey,
            FeedbackEventType.RESTORED,
            FeedbackActor.USER,
            clock.instant())
            .reason(FeedbackLabeler.ATTENTION_RESTORE);
        writeControl(
            user,
            card,
            itemKey,
            new ControlEntry(
                entry,
                card.name(),
                null,
                AttentionReactionCodec.controlEventKey(requestKey, FeedbackEventType.RESTORED, card.name(), itemKey)),
            // 읽은 값이 비어 있어도 복원 사건은 남긴다. 아직 커밋되지 않은 숨김과 겹쳤을 때 복원이 빠지지 않게 한다.
            latest -> latest.filter(record -> record.type() == FeedbackEventType.RESTORED).isPresent());
    }


    /**
     * 사용자가 항목을 열었거나({@code OPENED}) 단추로 동작한({@code ACTED}) 사건을 남긴다.
     *
     * <p>지금 후보에 있거나 그 요청자에게 같은 항목, 같은 상태의 {@code SHOWN} 사건이 있으면 받는다. 동작이 성공하면 그 항목이
     * 후보에서 빠지므로, 그 뒤에 보내는 {@code ACTED} 를 잃지 않게 하려는 것이다. 상태까지 맞춰 보인 적 없는 상태의 줄이 쌓이지
     * 않게 한다.
     *
     * @param type 모르는 사건 글이면 null
     */
    public void record(CurrentUser user, String itemKey, String stateKey, AttentionEventType type) {
        requireItemKey(itemKey);
        requireStateKey(stateKey);
        if (type != AttentionEventType.OPENED && type != AttentionEventType.ACTED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "type must be OPENED or ACTED");
        }
        AttentionSnapshot snapshot = attention.snapshot(user);
        Judged judged = judgedInCards(snapshot, List.of(CardKey.values()), itemKey)
            .or(() -> lastShownInState(user, itemKey, stateKey))
            .or(() -> suppressedInCandidates(snapshot, List.of(CardKey.values()), itemKey))
            .orElseThrow(AttentionControlService::notFound);
        writer.recordOnce(
            new AttentionReaction(user.id(), itemKey, stateKey, judged.trigger(), judged.level(), type, snapshot.now()));
    }


    /**
     * 제어 사건을 짧은 트랜잭션에서 필수로 남긴다. 마지막 제어 사건이 이미 같은 상태이면 사건을 더하지 않는다. 같은 요청 키가 동시에 와
     * 유일 제약에 걸렸을 때, 그 키의 사건이 이미 있으면 먼저 들어간 요청이 해낸 것이라 성공으로 본다.
     *
     * @param alreadyApplied 마지막 제어 사건이 이 요청의 결과와 같은가
     */
    private void writeControl(
        CurrentUser user,
        CardKey card,
        String itemKey,
        ControlEntry entry,
        Predicate<Optional<ReactionRecord>> alreadyApplied) {
        try {
            transactions.executeWithoutResult(status -> {
                if (!alreadyApplied.test(ledger.latestControl(user.id(), card.name(), itemKey))) {
                    ledger.append(entry);
                }
            });
        } catch (DataIntegrityViolationException ex) {
            String key = entry.eventKey();
            if (key == null || !ledger.existingKeys(user.id(), List.of(key)).contains(key)) {
                throw ex;
            }
        }
    }


    /** 판정 결과의 그 카드들에 그 항목이 있으면, {@link CardKey} 선언 순서로 처음 나온 항목의 {@code trigger} 와 판정이다. */
    private static Optional<Judged> judgedInCards(AttentionSnapshot snapshot, List<CardKey> keys, String itemKey) {
        for (CardKey key : keys) {
            for (AttentionCard card : snapshot.cards()) {
                if (card.key() != key) {
                    continue;
                }
                for (AttentionItem item : card.items()) {
                    if (item.itemKey().equals(itemKey)) {
                        return Optional.of(new Judged(item.trigger(), item.level()));
                    }
                }
            }
        }
        return Optional.empty();
    }


    /**
     * 요청자의 {@code SHOWN} 사건 가운데 항목과 상태가 같은 가장 최근 것의 {@code trigger} 와 판정이다. 이관한 옛 사건처럼 판정이 없으면
     * {@code LATER} 로 본다.
     */
    private Optional<Judged> lastShownInState(CurrentUser user, String itemKey, String stateKey) {
        return ledger.lastShown(user.id(), itemKey, stateKey)
            .flatMap(shown -> AttentionReactionCodec.triggerOf(shown.reasonCode())
                .map(trigger -> new Judged(
                    trigger, AttentionReactionCodec.levelOf(shown.reasonCode()).orElse(AttentionLevel.LATER))));
    }


    /** 억제 전 후보에만 있으면(숨겼거나 미뤘다) 그 후보의 {@code trigger} 와 {@code SUPPRESSED} 다. */
    private static Optional<Judged> suppressedInCandidates(
        AttentionSnapshot snapshot, List<CardKey> keys, String itemKey) {
        return keys.stream()
            .flatMap(key -> snapshot.candidates().getOrDefault(key, List.of()).stream())
            .filter(candidate -> candidate.itemKey().equals(itemKey))
            .findFirst()
            .map(candidate -> new Judged(candidate.trigger(), AttentionLevel.SUPPRESSED));
    }


    private static AttentionCandidate requireCandidate(AttentionSnapshot snapshot, CardKey card, String itemKey) {
        return snapshot.candidates().getOrDefault(card, List.of()).stream()
            .filter(candidate -> candidate.itemKey().equals(itemKey))
            .findFirst()
            .orElseThrow(AttentionControlService::notFound);
    }


    private static void requireItemKey(String itemKey) {
        if (itemKey == null || itemKey.isBlank() || itemKey.length() > ITEM_KEY_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "itemKey must be 1 to 80 characters");
        }
    }


    private static void requireStateKey(String stateKey) {
        if (stateKey == null || stateKey.isBlank() || stateKey.length() > STATE_KEY_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "stateKey must be 1 to 64 characters");
        }
    }


    private static void requireRequestKey(String requestKey) {
        if (requestKey != null && (requestKey.isBlank() || requestKey.length() > REQUEST_KEY_MAX)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "requestKey must be 1 to 100 characters");
        }
    }


    private static void requireCard(CardKey card) {
        if (card == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "card must be one of the four card keys");
        }
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.ATTENTION_ITEM_NOT_FOUND, "attention item not found");
    }


    /** 사건에 남길 판정 칸이다. 이 클래스 밖에서 쓰이지 않는다. */
    private record Judged(AttentionTrigger trigger, AttentionLevel level) {
    }
}
