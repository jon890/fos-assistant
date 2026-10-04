package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.application.model.AttentionMetric;
import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.infra.AttentionEventRepository;
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
 * 지표 사건을 {@code trigger} 별로 센다. 셈은 {@code docs/backend/attention.md} 의 「지표」 가 갖는다.
 *
 * <p>항목 하나는 같은 사용자의 같은 {@code (itemKey, stateKey)} 다. 기간 안에 {@code SHOWN} 이 있는 항목만 세고, 그 항목의
 * {@code trigger} 는 그 {@code SHOWN} 의 것이다.
 */
@Service
@RequiredArgsConstructor
public class AttentionMetricsService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final AttentionEventRepository events;
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
        Map<String, List<AttentionEvent>> byItem = new LinkedHashMap<>();
        for (AttentionEvent event : events.findByCreatedAtGreaterThanEqual(since)) {
            byItem.computeIfAbsent(identity(event), key -> new ArrayList<>()).add(event);
        }
        Map<AttentionTrigger, Tally> tallies = new EnumMap<>(AttentionTrigger.class);
        for (List<AttentionEvent> itemEvents : byItem.values()) {
            firstOf(itemEvents, AttentionEventType.SHOWN)
                    .ifPresent(shown -> tallies.computeIfAbsent(shown.trigger(), trigger -> new Tally())
                            .add(itemEvents, shown));
        }
        List<AttentionMetric> rows = new ArrayList<>();
        tallies.forEach((trigger, tally) -> rows.add(tally.toMetric(trigger)));
        return List.copyOf(rows);
    }

    private static String identity(AttentionEvent event) {
        return event.userId() + "|" + event.itemKey() + "|" + event.stateKey();
    }

    private static Optional<AttentionEvent> firstOf(List<AttentionEvent> events, AttentionEventType... types) {
        return events.stream()
                .filter(event -> List.of(types).contains(event.eventType()))
                .min(Comparator.comparing(AttentionEvent::createdAt).thenComparing(AttentionEvent::id));
    }

    private static boolean has(List<AttentionEvent> events, AttentionEventType type) {
        return events.stream().anyMatch(event -> event.eventType() == type);
    }

    /** 한 {@code trigger} 의 항목을 세는 그릇이다. 이 클래스 밖에서 쓰이지 않는다. */
    private static final class Tally {
        private long shown;
        private long hidden;
        private long snoozed;
        private long acted;
        private long nowShown;
        private long nowHiddenWithoutAction;
        private long staleShown;
        private final List<Long> secondsToFirstAction = new ArrayList<>();

        /** 한 항목의 사건을 더한다. {@code firstShown} 은 그 항목의 첫 {@code SHOWN} 이다. */
        void add(List<AttentionEvent> itemEvents, AttentionEvent firstShown) {
            shown++;
            boolean wasHidden = has(itemEvents, AttentionEventType.HIDDEN);
            Optional<AttentionEvent> firstAction =
                    firstOf(itemEvents, AttentionEventType.OPENED, AttentionEventType.ACTED);
            if (wasHidden) {
                hidden++;
            }
            if (has(itemEvents, AttentionEventType.SNOOZED)) {
                snoozed++;
            }
            firstAction.ifPresent(action -> {
                acted++;
                secondsToFirstAction.add(Math.max(
                        0,
                        Duration.between(firstShown.createdAt(), action.createdAt())
                                .toSeconds()));
            });
            boolean shownAsNow = itemEvents.stream()
                    .anyMatch(event ->
                            event.eventType() == AttentionEventType.SHOWN && event.level() == AttentionLevel.NOW);
            if (shownAsNow) {
                nowShown++;
                if (wasHidden && firstAction.isEmpty()) {
                    nowHiddenWithoutAction++;
                }
            }
            if (itemEvents.stream().anyMatch(event -> event.eventType() == AttentionEventType.SHOWN && event.stale())) {
                staleShown++;
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
                    staleShown,
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
