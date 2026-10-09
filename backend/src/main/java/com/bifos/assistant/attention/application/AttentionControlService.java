package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionSnapshot;
import com.bifos.assistant.attention.domain.AttentionControlEntry;
import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.attention.infra.AttentionControlRepository;
import com.bifos.assistant.attention.infra.AttentionEventRepository;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지금 화면의 숨기기, 미루기, 되돌리기와 사용자가 항목에 한 일의 사건을 받는다. 계약은 {@code docs/features/attention.md} 의
 * 「API」 가 갖는다.
 *
 * <p>메서드에 {@code @Transactional} 을 붙이지 않는다. 제어 줄은 여기서 연 트랜잭션 안에서 저장하고, 같은 요청이 동시에 와 유일
 * 제약에 걸리면 그 트랜잭션 밖에서 다시 읽어 고친다. 메서드 전체를 한 트랜잭션으로 묶으면 예외를 잡아도 rollback-only 로 남아 커밋할
 * 때 실패한다. 사건은 제어 줄과 다른 트랜잭션이라, 사건을 남기지 못해도 숨기기와 미루기는 남는다.
 *
 * <p>숨기기와 미루기는 요청자의 지금 후보에 있는 항목만 받는다. 남의 항목과 없는 항목을 같은 응답으로 숨긴다.
 */
@Service
public class AttentionControlService {

    /** {@code attention_control.item_key}, {@code attention_event.item_key} 의 칸 길이다. */
    private static final int ITEM_KEY_MAX = 80;

    /** {@code attention_control.state_key}, {@code attention_event.state_key} 의 칸 길이다. */
    private static final int STATE_KEY_MAX = 64;

    private final AttentionService attention;
    private final AttentionControlRepository controls;
    private final AttentionEventRepository events;
    private final AttentionEventWriter writer;
    private final AttentionProperties properties;
    private final SuggestionFeedback suggestions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. 제어 줄을 저장하는 트랜잭션을 여기서 만든다.
    public AttentionControlService(
            AttentionService attention,
            AttentionControlRepository controls,
            AttentionEventRepository events,
            AttentionEventWriter writer,
            AttentionProperties properties,
            SuggestionFeedback suggestions,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.attention = attention;
        this.controls = controls;
        this.events = events;
        this.writer = writer;
        this.properties = properties;
        this.suggestions = suggestions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 그 카드에서 클라이언트가 본 상태가 바뀔 때까지 숨긴다. 서버가 지금의 상태로 바꿔 넣지 않는다. 보고 누르는 사이에 상태가
     * 바뀌었으면 새 상태는 사용자가 보지 않은 것이라 다시 보여야 한다.
     *
     * @param card 모르는 카드 글이면 null
     */
    public void hide(CurrentUser user, CardKey card, String itemKey, String stateKey) {
        requireItemKey(itemKey);
        requireStateKey(stateKey);
        requireCard(card);
        AttentionSnapshot snapshot = attention.snapshot(user);
        AttentionCandidate candidate = requireCandidate(snapshot, card, itemKey);
        Instant now = snapshot.now();
        saveControl(
                user,
                card,
                itemKey,
                entry -> entry.rehide(stateKey, now),
                () -> AttentionControlEntry.hide(user.id(), card, itemKey, stateKey, now));
        recordInCard(user, snapshot, card, itemKey, stateKey, AttentionEventType.HIDDEN);
        suggestions.controlled(user, candidate, FeedbackEventType.DISMISSED, FeedbackLabeler.ATTENTION_HIDE, now);
    }

    /**
     * 그 카드에서 {@code until} 까지 미룬다. {@code until} 은 지금보다 뒤이고 지금부터 {@code snooze-max} 안이어야 한다.
     *
     * @param card 모르는 카드 글이면 null
     */
    public void snooze(CurrentUser user, CardKey card, String itemKey, Instant until) {
        requireItemKey(itemKey);
        requireCard(card);
        Instant requestedAt = clock.instant();
        if (until == null || !until.isAfter(requestedAt) || until.isAfter(requestedAt.plus(properties.snoozeMax()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "until must be after now and within the snooze limit");
        }
        AttentionSnapshot snapshot = attention.snapshot(user);
        AttentionCandidate candidate = requireCandidate(snapshot, card, itemKey);
        Instant now = snapshot.now();
        saveControl(
                user,
                card,
                itemKey,
                entry -> entry.resnooze(until, now),
                () -> AttentionControlEntry.snooze(user.id(), card, itemKey, until, now));
        // 미루기 요청에는 상태가 없어 그 카드 후보의 지금 상태로 사건을 남긴다.
        recordInCard(user, snapshot, card, itemKey, candidate.stateKey(), AttentionEventType.SNOOZED);
        suggestions.controlled(user, candidate, FeedbackEventType.POSTPONED, FeedbackLabeler.ATTENTION_SNOOZE, now);
    }

    /**
     * 그 카드의 그 항목의 숨기기와 미루기를 지운다. 지운 것이 없어도 끝난다.
     *
     * @param card 모르는 카드 글이면 null
     */
    public void restore(CurrentUser user, CardKey card, String itemKey) {
        requireItemKey(itemKey);
        requireCard(card);
        transactions.executeWithoutResult(
                status -> controls.deleteByUserIdAndCardKeyAndItemKey(user.id(), card, itemKey));
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
        writer.recordOnce(event(user, itemKey, stateKey, judged, type, snapshot.now()));
    }

    /**
     * 제어 줄을 고치거나 만든다. 같은 요청이 동시에 와 유일 제약에 걸리면 먼저 들어간 줄을 다시 읽어 한 번 더 고친다.
     *
     * @param change 이미 있는 줄을 고치는 방법
     * @param create 줄이 없을 때 만드는 방법
     */
    private void saveControl(
            CurrentUser user,
            CardKey card,
            String itemKey,
            Consumer<AttentionControlEntry> change,
            Supplier<AttentionControlEntry> create) {
        try {
            transactions.executeWithoutResult(status -> {
                Optional<AttentionControlEntry> existing =
                        controls.findByUserIdAndCardKeyAndItemKey(user.id(), card, itemKey);
                if (existing.isPresent()) {
                    change.accept(existing.get());
                } else {
                    controls.saveAndFlush(create.get());
                }
            });
        } catch (DataIntegrityViolationException ex) {
            transactions.executeWithoutResult(
                    status -> change.accept(controls.findByUserIdAndCardKeyAndItemKey(user.id(), card, itemKey)
                            .orElseThrow(() -> ex)));
        }
    }

    /** 숨기기와 미루기의 사건이다. 판정 칸은 요청의 카드 안에서만 찾고, 없으면 가장 최근 {@code SHOWN} 을 본다. */
    private void recordInCard(
            CurrentUser user,
            AttentionSnapshot snapshot,
            CardKey card,
            String itemKey,
            String stateKey,
            AttentionEventType type) {
        judgedInCards(snapshot, List.of(card), itemKey)
                .or(() -> lastShown(user, itemKey))
                .or(() -> suppressedInCandidates(snapshot, List.of(card), itemKey))
                .ifPresent(judged -> writer.recordOnce(event(user, itemKey, stateKey, judged, type, snapshot.now())));
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
     * 그 요청자의 그 항목의 가장 최근 {@code SHOWN} 사건의 {@code trigger} 와 판정이다.
     *
     * <p>숨기기와 미루기 전용이고 상태와 상관없이 본다. 그 요청은 본문에 {@code stateKey} 가 없어 지금 후보의 상태로 사건을 남기므로,
     * 상태가 바뀐 뒤에도 그 항목이 보였던 판정을 이어 받는다. {@code OPENED} 와 {@code ACTED} 는 {@link #lastShownInState} 를 쓴다.
     */
    private Optional<Judged> lastShown(CurrentUser user, String itemKey) {
        return events.findFirstByUserIdAndItemKeyAndEventTypeOrderByIdDesc(user.id(), itemKey, AttentionEventType.SHOWN)
                .map(shown -> new Judged(shown.trigger(), shown.level()));
    }

    /** 요청자의 {@code SHOWN} 사건 가운데 항목과 상태가 같은 가장 최근 것의 {@code trigger} 와 판정이다. */
    private Optional<Judged> lastShownInState(CurrentUser user, String itemKey, String stateKey) {
        return events.findFirstByUserIdAndItemKeyAndStateKeyAndEventTypeOrderByIdDesc(
                        user.id(), itemKey, stateKey, AttentionEventType.SHOWN)
                .map(shown -> new Judged(shown.trigger(), shown.level()));
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

    private static AttentionEvent event(
            CurrentUser user, String itemKey, String stateKey, Judged judged, AttentionEventType type, Instant now) {
        return AttentionEvent.of(user.id(), itemKey, stateKey, judged.trigger(), judged.level(), type, false, now);
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

    private static void requireCard(CardKey card) {
        if (card == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "card must be one of the four card keys");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ATTENTION_ITEM_NOT_FOUND, "attention item not found");
    }

    /** 사건에 남길 판정 칸이다. 이 클래스 밖에서 쓰이지 않는다. */
    private record Judged(AttentionTrigger trigger, AttentionLevel level) {}
}
