package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.dto.AttentionMetric;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.feedback.application.dto.ReactionRecord;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지표 사건을 {@code trigger} 별로 센다. 셈은 {@code docs/features/attention.md} 의 「지표(먼저 알리기와 지금 화면의 판정)」 가 갖는다.
 *
 * <p>항목 하나는 같은 사용자의 같은 {@code (itemKey, stateKey)} 다. 기간 안에 {@code SHOWN} 이 있는 항목만 세고, 그 항목의
 * {@code trigger} 는 그 {@code SHOWN} 의 것이다.
 */
@Service
@RequiredArgsConstructor
public class AttentionMetricsService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final ReactionLedger ledger;
    private final Clock clock;


    /**
     * 지금부터 {@code days} 일 전 이후의 사건을 센다.
     *
     * @return {@link AttentionTrigger} 선언 순서다. 보인 항목이 없는 {@code trigger} 는 줄이 없다
     */
    public List<AttentionMetric> metrics(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "days must be 1 to 90");
        }
        Instant since = clock.instant().minus(Duration.ofDays(days));
        Map<String, List<Row>> byItem = new LinkedHashMap<>();
        for (ReactionRecord record : ledger.attentionMetricEventsSince(
            since, List.of(FeedbackLabeler.ATTENTION_HIDE, FeedbackLabeler.ATTENTION_SNOOZE))) {
            row(record).ifPresent(row -> byItem.computeIfAbsent(identity(row), key -> new ArrayList<>()).add(row));
        }
        Map<AttentionTrigger, Tally> tallies = new EnumMap<>(AttentionTrigger.class);
        for (List<Row> itemEvents : byItem.values()) {
            firstOf(itemEvents, AttentionEventType.SHOWN)
                .ifPresent(shown -> tallies.computeIfAbsent(shown.trigger(), trigger -> new Tally())
                    .add(itemEvents, shown));
        }
        List<AttentionMetric> rows = new ArrayList<>();
        tallies.forEach((trigger, tally) -> rows.add(tally.toMetric(trigger)));
        return List.copyOf(rows);
    }


    /**
     * 반응 기록의 줄을 지표 사건으로 읽는다. 노출, 열기, 동작은 {@code reason_code} 에서 계기와 판정을 읽고, 숨김과 미루기는 계기를 모르므로
     * 계기가 필요 없는 칸만 쓴다. 계기를 읽지 못하는 노출 줄은 센 항목이 아니라 건너뛴다.
     */
    private static Optional<Row> row(ReactionRecord record) {
        AttentionEventType type = switch (record.type()) {
            case SHOWN -> AttentionEventType.SHOWN;
            case OPENED -> AttentionEventType.OPENED;
            case ACTED -> AttentionEventType.ACTED;
            case DISMISSED -> AttentionEventType.HIDDEN;
            case POSTPONED -> AttentionEventType.SNOOZED;
            default -> null;
        };
        if (type == null || record.stateKey() == null) {
            return Optional.empty();
        }
        if (type == AttentionEventType.HIDDEN || type == AttentionEventType.SNOOZED) {
            return Optional.of(new Row(record.id(), record.userId(), record.subjectKey(), record.stateKey(), type, null, null, record.occurredAt()));
        }
        return AttentionReactionCodec.triggerOf(record.reasonCode())
            .map(trigger -> new Row(
                record.id(),
                record.userId(),
                record.subjectKey(),
                record.stateKey(),
                type,
                trigger,
                AttentionReactionCodec.levelOf(record.reasonCode()).orElse(null),
                record.occurredAt()));
    }


    private static String identity(Row row) {
        return row.userId() + "|" + row.itemKey() + "|" + row.stateKey();
    }


    private static Optional<Row> firstOf(List<Row> events, AttentionEventType... types) {
        return events.stream()
            .filter(event -> List.of(types).contains(event.type()))
            .min(Comparator.comparing(Row::at).thenComparing(Row::id));
    }


    private static boolean has(List<Row> events, AttentionEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }


    /**
     * 지표가 세는 사건 하나다. {@code trigger} 와 {@code level} 은 노출, 열기, 동작에만 있고, 이관한 옛 사건은 {@code level} 이 비어 있다.
     */
    private record Row(
        Long id,
        Long userId,
        String itemKey,
        String stateKey,
        AttentionEventType type,
        AttentionTrigger trigger,
        AttentionLevel level,
        Instant at) {
    }

    /** 한 {@code trigger} 의 항목을 세는 그릇이다. 이 클래스 밖에서 쓰이지 않는다. */
    private static final class Tally {
        private long shown;
        private long hidden;
        private long snoozed;
        private long acted;
        private long nowShown;
        private long nowHiddenWithoutAction;
        private final List<Long> secondsToFirstAction = new ArrayList<>();


        /** 한 항목의 사건을 더한다. {@code firstShown} 은 그 항목의 첫 {@code SHOWN} 이다. */
        void add(List<Row> itemEvents, Row firstShown) {
            shown++;
            boolean wasHidden = has(itemEvents, AttentionEventType.HIDDEN);
            Optional<Row> firstAction = firstOf(itemEvents, AttentionEventType.OPENED, AttentionEventType.ACTED);
            if (wasHidden) {
                hidden++;
            }
            if (has(itemEvents, AttentionEventType.SNOOZED)) {
                snoozed++;
            }
            firstAction.ifPresent(action -> {
                acted++;
                secondsToFirstAction.add(Math.max(
                    // 지금의 후보는 모두 판정할 때 읽은 기록이라 신선도가 늘 FRESH 다. 반응 기록은 신선도를 담지 않는다.
                    0,
                    Duration.between(firstShown.at(), action.at())
                        .toSeconds()));
            });
            boolean shownAsNow = itemEvents.stream()
                .anyMatch(event -> event.type() == AttentionEventType.SHOWN && event.level() == AttentionLevel.NOW);
            if (shownAsNow) {
                nowShown++;
                if (wasHidden && firstAction.isEmpty()) {
                    nowHiddenWithoutAction++;
                }
            }
        }


        AttentionMetric toMetric(AttentionTrigger trigger) {
            return new AttentionMetric(
                trigger,
                shown,
                hidden,
                snoozed,
                acted,
                nowShown,
                nowHiddenWithoutAction,
                // 지금의 후보는 모두 판정할 때 읽은 기록이라 신선도가 늘 FRESH 다. 반응 기록은 신선도를 담지 않는다.
                0,
                median(secondsToFirstAction));
        }


        /** 짝수 개면 가운데 둘의 평균을 내림한다. 없으면 null 이다. */
        private static Long median(List<Long> values) {
            if (values.isEmpty()) {
                return null;
            }
            List<Long> sorted = values.stream().sorted().toList();
            int middle = sorted.size() / 2;
            if (sorted.size() % 2 == 1) {
                return sorted.get(middle);
            }
            return Math.floorDiv(sorted.get(middle - 1) + sorted.get(middle), 2L);
        }
    }
}
