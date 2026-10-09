package com.bifos.assistant.proactive.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.proactive.application.model.DecisionReaction;
import com.bifos.assistant.proactive.application.model.SurfacedProblem;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 루프가 낸 {@code SURFACE}, {@code ASK_APPROVAL} 판정을 지금 화면에 보일 문제로 읽고, 그 판정의 {@code SURFACED} 와 사용자
 * 반응을 판단 피드백 사건으로 남긴다. 고르는 순서와 규칙은 {@code docs/features/proactive.md} 의 「사용자에게 보이는 것」 이 갖는다.
 *
 * <p>사건 열쇠는 {@code autonomy_decision:<판정 번호>} 다. 반응은 기록만 하고 할 일, 승인 줄, 실행을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SurfacedProblems {

    private final ProactiveLoopRunRepository runs;
    private final AutonomyDecisionRepository decisions;
    private final ProactiveCheckProblemRepository problems;
    private final ProactiveCheckRepository checks;
    private final FeedbackEventRepository events;
    private final DecisionFeedbackRecorder feedback;
    private final LiveProperties<ProactiveLoopProperties> properties;
    private final Clock clock;

    /**
     * 요청자에게 지금 보일 판정을 늦게 남긴 것부터 모두 낸다. {@code surface-max-items} 상한은 걸지 않는다. 지운 점검 대화와 찾지 못한 에이전트는
     * 부르는 쪽이 거른 뒤에 상한을 걸어야, 지운 판정이 최근 자리를 차지해 유효한 판정을 밀어내지 않는다.
     *
     * <p>판정을 읽고 나서 후보 줄이나 살펴보기 줄이 없거나 문제 글이 비었으면 그 판정만 뺀다.
     */
    @Transactional(readOnly = true)
    public List<SurfacedProblem> openOf(Long userId, Instant now) {
        ProactiveLoopProperties loop = properties.current();
        Instant since = now.minus(loop.surfaceWindow());
        Set<Long> evaluationIds =
                runs.findByUserIdAndStatusAndCreatedAtAfter(userId, LoopRunStatus.DECIDED, since).stream()
                        .map(ProactiveLoopRun::evaluationId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        List<AutonomyDecision> loopDecisions = loopDecisions(userId, evaluationIds);
        if (loopDecisions.isEmpty()) {
            return List.of();
        }
        Map<Long, ProactiveCheckProblem> problemsById =
                problems
                        .findAllById(loopDecisions.stream()
                                .map(AutonomyDecision::candidateId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ProactiveCheckProblem::id, Function.identity()));
        Map<Long, ProactiveCheck> checksById =
                checks
                        .findAllById(loopDecisions.stream()
                                .map(AutonomyDecision::sourceCheckId)
                                .toList())
                        .stream()
                        .filter(check -> userId.equals(check.userId()))
                        .collect(Collectors.toMap(ProactiveCheck::id, Function.identity()));
        List<Surfaced> readable = new ArrayList<>();
        for (AutonomyDecision decision : loopDecisions) {
            ProactiveCheckProblem problem = problemsById.get(decision.candidateId());
            ProactiveCheck check = checksById.get(decision.sourceCheckId());
            if (problem == null
                    || check == null
                    || !problem.checkId().equals(check.id())
                    || problem.problem() == null
                    || problem.problem().isBlank()) {
                continue;
            }
            readable.add(new Surfaced(decision, problem, check));
        }
        // 같은 문제 키는 가장 늦게 남긴 판정 하나를 먼저 고른 뒤에 반응을 본다. 반응을 먼저 빼면 새 판정을 거절한 뒤 옛 판정이 되살아난다.
        Map<String, Surfaced> latestPerKey = readable.stream()
                .collect(Collectors.toMap(
                        Surfaced::key,
                        Function.identity(),
                        (first, second) -> LATEST.compare(first, second) >= 0 ? first : second));
        Map<Long, DecisionReaction> reactions = current(
                userId,
                latestPerKey.values().stream()
                        .map(surfaced -> surfaced.decision().id())
                        .toList());
        return latestPerKey.values().stream()
                .filter(surfaced -> !reactions.containsKey(surfaced.decision().id()))
                .sorted(LATEST.reversed())
                .map(Surfaced::view)
                .toList();
    }

    /** 판정마다 마지막 사용자 반응이다. 반응이 없는 판정은 맵에 없다. 지금 화면의 숨기기는 반응이 아니다. */
    public Map<Long, DecisionReaction> current(Long userId, Collection<Long> decisionIds) {
        if (decisionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> idsByKey = new HashMap<>();
        for (Long id : decisionIds) {
            idsByKey.put(FeedbackSubjectType.AUTONOMY_DECISION.key(id), id);
        }
        Map<Long, DecisionReaction> reactions = new HashMap<>();
        for (FeedbackEvent event :
                events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(userId, idsByKey.keySet())) {
            DecisionReaction reaction = DecisionReaction.of(event.eventType());
            if (event.actor() == FeedbackActor.USER && reaction != null && !hidden(event)) {
                reactions.put(idsByKey.get(event.subjectKey()), reaction);
            }
        }
        return reactions;
    }

    /**
     * 판정 하나에 반응한다. 지금 반응과 같으면 사건을 더 남기지 않는다.
     *
     * @throws ApiException 남의 판정, 없는 판정, 다른 수준의 판정, 루프 밖의 판정이면 {@code AUTONOMY_DECISION_NOT_FOUND}
     */
    public void react(CurrentUser user, Long decisionId, DecisionReaction reaction) {
        AutonomyDecision decision = decisionId == null
                ? null
                : decisions
                        .findById(decisionId)
                        .filter(row -> user.id().equals(row.userId()))
                        .orElse(null);
        if (decision == null || !isLoopDecision(user.id(), decision)) {
            throw new ApiException(ErrorCode.AUTONOMY_DECISION_NOT_FOUND, "no such surfaced decision");
        }
        if (current(user.id(), List.of(decisionId)).get(decisionId) == reaction) {
            return;
        }
        feedback.record(entry(decision, reaction.eventType(), FeedbackActor.USER));
    }

    /** 매일 루프가 보인 판정에 {@code SURFACED} 를 남긴다. {@code SURFACE}, {@code ASK_APPROVAL} 이 아니면 아무것도 하지 않는다. */
    public void surfaced(AutonomyDecision decision) {
        if (!surfaceable(decision)) {
            return;
        }
        feedback.record(entry(decision, FeedbackEventType.SURFACED, FeedbackActor.SYSTEM));
    }

    /** 숨기기와 미루기 사건에 원천 점검 대화, 원천 살펴보기, 판정을 채운다. 요청자의 판정이 아니거나 원천을 찾지 못하면 빈 값이다. */
    public Optional<FeedbackEntry> withOrigin(Long userId, Long decisionId, FeedbackEntry base) {
        return decisions
                .findById(decisionId)
                .filter(decision -> userId.equals(decision.userId()))
                .flatMap(decision -> checks.findById(decision.sourceCheckId())
                        .map(check -> base.conversation(check.conversationId())
                                .sourceCheck(check.id())
                                .autonomyDecision(decision.id())));
    }

    /**
     * 그 평가들 가운데 루프의 판정만 낸다. 평가와 후보마다 가장 먼저 남긴 줄 하나가 루프의 판정이고, 같은 평가를 판정 API 로 다시 판정한
     * 줄은 루프의 판정이 아니다. 그 가운데 {@code SURFACE}, {@code ASK_APPROVAL} 만 남긴다.
     */
    private List<AutonomyDecision> loopDecisions(Long userId, Collection<Long> evaluationIds) {
        if (evaluationIds.isEmpty()) {
            return List.of();
        }
        Set<List<Long>> seen = new HashSet<>();
        List<AutonomyDecision> first = new ArrayList<>();
        for (AutonomyDecision decision : decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(userId, evaluationIds)) {
            if (seen.add(List.of(decision.evaluationId(), decision.candidateId()))) {
                first.add(decision);
            }
        }
        return first.stream().filter(SurfacedProblems::surfaceable).toList();
    }

    /** 그 판정이 요청자의 {@code DECIDED} 시도가 만든 평가의 루프 판정인가. 보이는 기간은 보지 않는다. */
    private boolean isLoopDecision(Long userId, AutonomyDecision decision) {
        Set<Long> evaluationIds = runs
                .findByUserIdAndStatusAndEvaluationIdIn(userId, LoopRunStatus.DECIDED, List.of(decision.evaluationId()))
                .stream()
                .map(ProactiveLoopRun::evaluationId)
                .collect(Collectors.toSet());
        return loopDecisions(userId, evaluationIds).stream()
                .anyMatch(row -> row.id().equals(decision.id()));
    }

    private FeedbackEntry entry(AutonomyDecision decision, FeedbackEventType type, FeedbackActor actor) {
        return FeedbackEntry.of(
                        decision.userId(),
                        FeedbackSubjectType.AUTONOMY_DECISION,
                        decision.id(),
                        type,
                        actor,
                        clock.instant())
                .conversation(checks.findById(decision.sourceCheckId())
                        .map(ProactiveCheck::conversationId)
                        .orElse(null))
                .sourceCheck(decision.sourceCheckId())
                .autonomyDecision(decision.id());
    }

    private static boolean surfaceable(AutonomyDecision decision) {
        return decision.level() == AutonomyLevel.SURFACE || decision.level() == AutonomyLevel.ASK_APPROVAL;
    }

    private static boolean hidden(FeedbackEvent event) {
        return event.eventType() == FeedbackEventType.DISMISSED
                && FeedbackLabeler.ATTENTION_HIDE.equals(event.reasonCode());
    }

    /** 늦게 남긴 판정이 크다. 같은 시각이면 번호가 큰 쪽이다. */
    private static final Comparator<Surfaced> LATEST = Comparator.comparing(
                    (Surfaced surfaced) -> surfaced.decision().createdAt())
            .thenComparing(surfaced -> surfaced.decision().id());

    private record Surfaced(AutonomyDecision decision, ProactiveCheckProblem problem, ProactiveCheck check) {

        /** 문제 키가 비었으면 다른 판정과 합치지 않도록 판정 번호로 구분한다. */
        String key() {
            String key = problem.problemKey();
            return key == null || key.isBlank() ? "decision:" + decision.id() : key;
        }

        SurfacedProblem view() {
            return new SurfacedProblem(
                    decision.id(),
                    decision.level(),
                    check.id(),
                    check.agentId(),
                    check.conversationId(),
                    problem.problem(),
                    problem.actionText(),
                    decision.createdAt());
        }
    }
}
