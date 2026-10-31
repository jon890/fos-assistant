package com.bifos.assistant.feedback.application;

import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.MaintenanceDetail;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기억 정비 사건(후보, 확인)을 남기고 읽는다(ADR-20261015 / memory-maintenance).
 *
 * <p>{@link DecisionFeedbackRecorder} 와 달리 부르는 쪽의 트랜잭션 안에서 바로 저장한다. 확인이 후보 사건의 번호를 알아야 하고, 이 사건은
 * 관측용이 아니라 정비 규칙과 화면이 읽는 상태이기 때문이다. 제목과 본문은 받지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceEventLog {

    private static final List<FeedbackEventType> REVIEWS = List.of(FeedbackEventType.MAINTENANCE_CONFIRMED, FeedbackEventType.MAINTENANCE_DENIED);
    private static final List<FeedbackEventType> UNWANTED = List.of(FeedbackEventType.MARKED_IRRELEVANT, FeedbackEventType.MARKED_WRONG);

    private final FeedbackEventRepository events;


    /** 후보 사건을 남긴다. 주체는 시스템이다. */
    @Transactional
    public FeedbackEvent propose(
        Long userId, Long memoryId, String action, int revision, MaintenanceDetail detail, Instant at) {
        return events.save(FeedbackEvent.maintenance(
            userId, memoryId, FeedbackEventType.MAINTENANCE_PROPOSED, FeedbackActor.SYSTEM, revision, action, detail, at));
    }


    /** 그 사용자가 그 항목에 그 동작의 후보를 그 revision 으로 이미 남겼는가. 열려 있든 확인했든 같은 후보를 다시 만들지 않는다. */
    @Transactional(readOnly = true)
    public boolean alreadyProposed(Long userId, Long memoryId, String action, int revision) {
        return events.existsByUserIdAndSubjectKeyAndEventTypeAndReasonCodeAndSubjectVersion(
            userId,
            FeedbackSubjectType.MEMORY.key(memoryId),
            FeedbackEventType.MAINTENANCE_PROPOSED,
            action,
            Integer.toString(revision));
    }


    /**
     * 그 사용자의 확인 전 후보다. 같은 열쇠와 동작의 후보 뒤에 확인 사건이 있으면 닫힌 것이다.
     *
     * @param peerUserIds 같은 후보를 받는 사용자들이다. 그룹 항목은 그 그룹의 관리자 모두가 같은 후보를 받으므로, 이 가운데 한 사람이
     * 확인해도 다른 사람의 후보가 닫힌다. 개인 항목만 있으면 자기 자신뿐이어도 된다
     */
    @Transactional(readOnly = true)
    public List<FeedbackEvent> openCandidates(Long userId, Collection<Long> peerUserIds) {
        List<FeedbackEvent> proposed = events.findByUserIdAndEventTypeOrderByOccurredAtAscIdAsc(
            userId, FeedbackEventType.MAINTENANCE_PROPOSED);
        if (proposed.isEmpty()) {
            return List.of();
        }
        Set<Long> voters = new HashSet<>(peerUserIds);
        voters.add(userId);
        List<FeedbackEvent> reviews = events.findByUserIdInAndEventTypeIn(voters, REVIEWS);
        List<FeedbackEvent> open = new ArrayList<>();
        for (FeedbackEvent candidate : proposed) {
            boolean closed = reviews.stream()
                .anyMatch(review -> review.subjectKey().equals(candidate.subjectKey())
                    && review.reasonCode().equals(candidate.reasonCode())
                    && review.id() > candidate.id());
            if (!closed) {
                open.add(candidate);
            }
        }
        return open;
    }


    /** 후보를 확인한 사건을 남긴다. 주체는 사용자이고 근거는 확인한 후보 사건의 번호다. */
    @Transactional
    public FeedbackEvent review(Long userId, FeedbackEvent candidate, boolean correct, Instant at) {
        FeedbackEventType type = correct ? FeedbackEventType.MAINTENANCE_CONFIRMED : FeedbackEventType.MAINTENANCE_DENIED;
        return events.save(FeedbackEvent.maintenance(
            userId,
            memoryIdOf(candidate),
            type,
            FeedbackActor.USER,
            Integer.parseInt(candidate.subjectVersion()),
            candidate.reasonCode(),
            MaintenanceDetail.reviewOf(candidate.id()),
            at));
    }


    /** 「관련 없음」 이나 「틀림」 단추 사건이 있는 기억의 번호와 그 수다. 누른 사람은 묻지 않는다. */
    @Transactional(readOnly = true)
    public Map<Long, Long> unwantedCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : events.countSubjectKeysOfTypes(UNWANTED)) {
            String key = (String)row[0];
            if (FeedbackSubjectType.ofKey(key) == FeedbackSubjectType.MEMORY) {
                counts.put(Long.parseLong(key.substring(FeedbackSubjectType.MEMORY.key("").length())), ((Number)row[1]).longValue());
            }
        }
        return counts;
    }


    /** 사건의 열쇠 {@code memory:<번호>} 에서 기억 번호를 꺼낸다. */
    public static Long memoryIdOf(FeedbackEvent event) {
        return Long.parseLong(event.subjectKey().substring(FeedbackSubjectType.MEMORY.key("").length()));
    }
}
