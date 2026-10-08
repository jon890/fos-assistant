package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.proactive.application.model.CheckFindingView;
import com.bifos.assistant.proactive.application.model.FindingReaction;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 살펴보기의 「새로 알릴 것」 발견에 대한 사용자 반응을 판단 피드백 사건으로 받고 읽는다(ADR-20261008 / check-finding-reaction).
 *
 * <p>사건 열쇠는 {@code check_finding:<발견 번호>} 다. 발견의 지금 반응은 그 발견의 마지막 사용자 사건이다. 남의 발견과 「참고」 발견은
 * 없는 발견과 같은 응답으로 숨긴다.
 */
@Component
@RequiredArgsConstructor
public class CheckFindingReactions {

    private static final Duration DAY = Duration.ofDays(1);

    private final ProactiveCheckFindingRepository findings;
    private final ProactiveCheckRepository checks;
    private final FeedbackEventRepository events;
    private final DecisionFeedbackRecorder feedback;
    private final ConversationAccess conversations;
    private final LiveProperties<ProactiveCheckProperties> properties;
    private final Clock clock;

    /** 요청자의 그 점검 대화에서 요청자의 살펴보기가 「새로 알릴 것」 으로 그린 발견을 오래된 것부터, 지금 반응과 함께 낸다. 남의 대화는 404 다. */
    public List<CheckFindingView> list(CurrentUser user, UUID conversationId) {
        Long conversation = conversations.requireOwnId(user, conversationId);
        List<ProactiveCheckFinding> rows =
                findings.findByConversationIdAndKindOrderByIdAsc(conversation, FindingKind.NEW);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ProactiveCheck> owned =
                checks
                        .findAllById(rows.stream()
                                .map(ProactiveCheckFinding::checkId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .filter(check -> Objects.equals(check.userId(), user.id()))
                        .collect(Collectors.toMap(ProactiveCheck::id, Function.identity()));
        List<ProactiveCheckFinding> visible =
                rows.stream().filter(row -> owned.containsKey(row.checkId())).toList();
        Map<Long, FindingReaction> reactions = current(
                user.id(), visible.stream().map(ProactiveCheckFinding::id).toList());
        return visible.stream()
                .map(row -> new CheckFindingView(
                        row.id(),
                        row.checkId(),
                        owned.get(row.checkId()).rootExecutionId(),
                        row.area(),
                        row.topicKey(),
                        row.title(),
                        reactions.get(row.id())))
                .toList();
    }

    /** 「관심 없음」 이 같은 주제를 내리는 기간을 하루 단위로 올림한 값이다. 화면이 단추 아래에 알린다. 1 보다 작지 않다. */
    public long dismissWindowDays() {
        Duration window = properties.current().digestWindow();
        long days = window.toDays();
        if (window.compareTo(DAY.multipliedBy(days)) > 0) {
            days++;
        }
        return Math.max(1, days);
    }

    /**
     * 발견 하나에 반응한다. 요청자의 살펴보기가 낸 「새로 알릴 것」 발견만 받는다. 지금 반응과 같으면 사건을 더 남기지 않는다.
     *
     * @throws ApiException 없거나 「참고」 이거나 남의 발견이면 {@code PROACTIVE_CHECK_NOT_FOUND}
     */
    public void react(CurrentUser user, Long findingId, FindingReaction reaction) {
        ProactiveCheckFinding finding = findingId == null
                ? null
                : findings.findById(findingId)
                        .filter(row -> row.kind() == FindingKind.NEW)
                        .orElse(null);
        ProactiveCheck check = finding == null
                ? null
                : checks.findById(finding.checkId())
                        .filter(row -> Objects.equals(row.userId(), user.id()))
                        .orElse(null);
        if (check == null) {
            throw new ApiException(ErrorCode.PROACTIVE_CHECK_NOT_FOUND, "no such check finding");
        }
        if (current(user.id(), List.of(findingId)).get(findingId) == reaction) {
            return;
        }
        feedback.record(entry(check, findingId, reaction.eventType(), FeedbackActor.USER));
    }

    /** 발견마다 마지막 사용자 반응이다. 반응이 없는 발견은 맵에 없다. */
    public Map<Long, FindingReaction> current(Long userId, Collection<Long> findingIds) {
        if (findingIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> idsByKey = new HashMap<>();
        for (Long id : findingIds) {
            idsByKey.put(FeedbackSubjectType.CHECK_FINDING.key(id), id);
        }
        Map<Long, FindingReaction> reactions = new HashMap<>();
        for (FeedbackEvent event :
                events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(userId, idsByKey.keySet())) {
            FindingReaction reaction = FindingReaction.of(event.eventType());
            if (event.actor() == FeedbackActor.USER && reaction != null) {
                reactions.put(idsByKey.get(event.subjectKey()), reaction);
            }
        }
        return reactions;
    }

    /** 보고를 보인 살펴보기의 「새로 알릴 것」 발견마다 {@code SURFACED} 를 남긴다. */
    public void surfaced(ProactiveCheck check) {
        for (ProactiveCheckFinding finding : findings.findByCheckIdAndKind(check.id(), FindingKind.NEW)) {
            feedback.record(entry(check, finding.id(), FeedbackEventType.SURFACED, FeedbackActor.SYSTEM));
        }
    }

    private FeedbackEntry entry(ProactiveCheck check, Long findingId, FeedbackEventType type, FeedbackActor actor) {
        return FeedbackEntry.of(
                        check.userId(), FeedbackSubjectType.CHECK_FINDING, findingId, type, actor, clock.instant())
                .conversation(check.conversationId())
                .originExecution(check.rootExecutionId())
                .sourceCheck(check.id());
    }
}
