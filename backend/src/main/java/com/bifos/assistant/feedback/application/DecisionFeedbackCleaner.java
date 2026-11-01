package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.config.DecisionFeedbackProperties;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
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
     * <p>단, 지금도 걸려 있는 지금 화면의 제어 사건(카드마다 항목마다 마지막 숨김, 아직 기한이 안 지난 미루기)은 지우지 않는다.
     * 현재 숨김과 미루기를 이 사건에서 구하므로, 지우면 사용자가 숨긴 항목이 다시 보인다.
     *
     * @return 지운 줄 수
     */
    @Transactional
    public int clean(Instant now) {
        Map<Long, List<String>> expired = events.findSubjectsLastOccurredBefore(now.minus(properties.retention())).stream()
            .collect(Collectors.groupingBy(
                row -> (Long)row[0], Collectors.mapping(row -> (String)row[1], Collectors.toList())));
        int deleted = expired.entrySet().stream()
            .mapToInt(entry -> deleteExpired(entry.getKey(), entry.getValue(), now))
            .sum();
        log.info("expired decision feedback events cleaned deleted={}", deleted);
        return deleted;
    }


    /** 그 제안들의 사건을 지우되 지금도 걸려 있는 마지막 제어 사건은 남긴다. */
    private int deleteExpired(Long userId, List<String> subjectKeys, Instant now) {
        List<Long> latest = events.findCurrentControlIds(userId, subjectKeys, ReactionLedger.controlTypes());
        List<Long> keep = latest.isEmpty()
            ? List.of()
            : events.findAllById(latest).stream().filter(event -> stillControls(event, now)).map(FeedbackEvent::id).toList();
        return keep.isEmpty()
            ? events.deleteOfSubjects(userId, subjectKeys)
            : events.deleteOfSubjectsExcept(userId, subjectKeys, keep);
    }


    /** 숨김은 상태가 바뀔 때까지, 미루기는 기한까지 걸려 있다. 복원은 아무것도 걸지 않는다. */
    private static boolean stillControls(FeedbackEvent event, Instant now) {
        return switch (event.eventType()) {
            case DISMISSED -> true;
            case POSTPONED -> event.untilAt() != null && event.untilAt().isAfter(now);
            default -> false;
        };
    }
}
