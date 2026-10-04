package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.LatencyRow;
import com.bifos.assistant.chat.application.model.LatencyStat;
import com.bifos.assistant.chat.application.model.LatencySummary;
import com.bifos.assistant.chat.domain.TurnTiming;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사용자 turn 의 첫 반응 시간을 날짜와 모델 단계별 중앙값과 90번째 백분위로 집계한다.
 *
 * <p>세는 turn 의 기준은 {@code docs/model-tiers.md} 의 「첫 반응 시간」 이 갖는다. 백분위는 데이터베이스가 아니라 여기서 구한다.
 * MySQL 과 H2 가 함께 받는 백분위 함수가 없고, 한 사람이 30일 동안 보낸 turn 은 수천 건 이하라서다. nearest-rank 방식이라 값 n 개를
 * 오름차순으로 두면 p 백분위는 {@code ceil(p / 100 * n)} 번째 값이다.
 *
 * <p>시각이 비어 있는 실행은 그 지표에서만 뺀다. 0 으로 채우지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FirstResponseLatencyService {

    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final ChatMessageRepository messages;
    private final ScheduledTurnExecutions scheduledTurns;
    private final Clock clock;

    /**
     * 그 사용자의 최근 {@code days} 일 첫 반응 시간을 집계한다.
     *
     * @param days 1 이상 90 이하
     * @throws ApiException 일수가 범위 밖이면 VALIDATION_FAILED
     */
    public LatencySummary summarize(Long userId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "days must be between 1 and 90");
        }
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(days));
        List<TurnTiming> timings = messages.findUserTurnTimings(userId, from, to);
        Set<Long> scheduled = scheduledTurns.scheduledAmong(
                timings.stream().map(TurnTiming::executionId).toList());
        Map<Group, List<TurnTiming>> groups = timings.stream()
                .filter(timing -> !scheduled.contains(timing.executionId()))
                .collect(Collectors.groupingBy(
                        timing -> new Group(
                                timing.requestReceivedAt()
                                        .atZone(HOUSEHOLD_ZONE)
                                        .toLocalDate(),
                                timing.modelTier()),
                        Collectors.toList()));
        List<LatencyRow> rows = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(GROUP_ORDER))
                .map(entry -> row(entry.getKey(), entry.getValue()))
                .toList();
        return new LatencySummary(days, rows);
    }

    private static LatencyRow row(Group group, List<TurnTiming> timings) {
        return new LatencyRow(
                group.date(),
                group.modelTier(),
                timings.size(),
                stat(timings, TurnTiming::requestReceivedAt, TurnTiming::firstDeltaAt),
                stat(timings, TurnTiming::requestReceivedAt, TurnTiming::submittedAt),
                stat(timings, TurnTiming::submittedAt, TurnTiming::firstDeltaAt));
    }

    private static LatencyStat stat(
            List<TurnTiming> timings, Function<TurnTiming, Instant> start, Function<TurnTiming, Instant> end) {
        List<Long> sorted = timings.stream()
                .filter(timing -> start.apply(timing) != null && end.apply(timing) != null)
                .map(timing ->
                        Duration.between(start.apply(timing), end.apply(timing)).toMillis())
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return new LatencyStat(0, null, null);
        }
        return new LatencyStat(sorted.size(), nearestRank(sorted, 50), nearestRank(sorted, 90));
    }

    /** 오름차순 값의 p 백분위다. {@code ceil(p / 100 * n)} 번째 값을 정수 계산으로 구한다. */
    private static long nearestRank(List<Long> sorted, int percentile) {
        int rank = (percentile * sorted.size() + 99) / 100;
        return sorted.get(rank - 1);
    }

    /** 날짜와 모델 단계가 같은 turn 의 묶음 키다. 단계가 없는 묶음은 null 이다. */
    private record Group(LocalDate date, ModelTier modelTier) {}

    /** 날짜 오름차순이고, 같은 날짜 안에서는 단계 선언 순서, 단계 없음이 마지막이다. */
    private static final Comparator<Group> GROUP_ORDER = Comparator.comparing(Group::date)
            .thenComparing(Group::modelTier, Comparator.nullsLast(Comparator.naturalOrder()));
}
