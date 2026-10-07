package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 보관 기간({@code retention})이 지난 판단 피드백 사건을 지운다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionFeedbackCleaner {

    private final FeedbackEventRepository events;
    private final DecisionFeedbackProperties properties;
    private final Clock clock;

    /** 하루에 한 번 돈다. 시각은 Control Plane 의 시간대를 따르고, 검사에서는 {@code -} 로 끈다. */
    @Scheduled(cron = "${assistant.decision-feedback.cleanup-cron}")
    @Transactional
    public void runScheduled() {
        clean(clock.instant());
    }

    /**
     * {@code now} 에서 보관 기간보다 먼저 마지막 사건이 일어난 제안의 사건을 모두 지운다. 사건 단위로 지우면 나중 사건만 남아 첫 반응을 잘못 읽는다.
     *
     * @return 지운 줄 수
     */
    @Transactional
    public int clean(Instant now) {
        Map<Long, List<String>> expired =
                events.findSubjectsLastOccurredBefore(now.minus(properties.retention())).stream()
                        .collect(Collectors.groupingBy(
                                row -> (Long) row[0], Collectors.mapping(row -> (String) row[1], Collectors.toList())));
        int deleted = expired.entrySet().stream()
                .mapToInt(entry -> events.deleteOfSubjects(entry.getKey(), entry.getValue()))
                .sum();
        log.info("expired decision feedback events cleaned deleted={}", deleted);
        return deleted;
    }
}
