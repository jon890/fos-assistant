package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.OwnConversations;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.model.SubjectLabel;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Candidate;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.CandidateAxes;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.DecisionRecord;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Event;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Judgment;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Policy;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Situation;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Subject;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청자의 판단 피드백을 결정 단위로 묶어 offline replay 읽기 모델로 낸다(ADR-20261007 decision-feedback).
 *
 * <p>상황은 그 기간에 연 살펴보기와 사건이 가리키는 살펴보기다. 제안은 살펴보기의 실행 트리에서 나왔거나 살펴보기를 가리키면 그 살펴보기의
 * 결정에 묶는다. 자동 실행한 살펴보기의 결과는 그 실행을 허락한 판정의 원천 살펴보기에 묶는다. 지운 대화에 묶인 상황과 제안은 싣지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DecisionFeedbackExporter {

    private static final String CHECK_KEY_PREFIX = "check:";

    private final FeedbackEventRepository events;
    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final ValueEvaluationRepository evaluations;
    private final AutonomyDecisionRepository decisions;
    private final OwnConversations conversations;
    private final Clock clock;

    /** @param window 지금부터 거슬러 읽을 기간 */
    public DecisionFeedbackExport export(CurrentUser user, Duration window) {
        Instant to = clock.instant();
        Instant from = to.minus(window);
        Map<String, List<FeedbackEvent>> subjects = visibleSubjects(user, from);
        Map<String, Long> subjectChecks = subjectChecks(user, subjects);

        Set<Long> checkIds = new HashSet<>(subjectChecks.values());
        checks.findByUserIdAndStartedAtGreaterThanEqualOrderByIdAsc(user.id(), from)
                .forEach(check -> checkIds.add(check.id()));
        Map<Long, ProactiveCheck> situations = activeChecks(user, checkIds);

        Map<Long, List<ProactiveCheckProblem>> candidates = situations.isEmpty()
                ? Map.of()
                : problems.findByCheckIdInOrderByIdAsc(situations.keySet()).stream()
                        .collect(Collectors.groupingBy(ProactiveCheckProblem::checkId));
        List<ValueEvaluation> judged = situations.isEmpty()
                ? List.of()
                : evaluations.findByUserIdAndCheckIdInOrderByIdAsc(user.id(), situations.keySet());
        Map<Long, List<ValueEvaluation>> judgments =
                judged.stream().collect(Collectors.groupingBy(ValueEvaluation::checkId));
        Map<Long, List<AutonomyDecision>> policies = judged.isEmpty()
                ? Map.of()
                : decisions
                        .findByUserIdAndEvaluationIdInOrderByIdAsc(
                                user.id(),
                                judged.stream().map(ValueEvaluation::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(AutonomyDecision::sourceCheckId));

        Map<String, List<Subject>> grouped = new LinkedHashMap<>();
        subjects.forEach((key, history) -> {
            Long checkId = subjectChecks.get(key);
            if (checkId != null && !situations.containsKey(checkId)) {
                // 묶을 살펴보기의 대화를 지웠다. 그 결정의 제안도 싣지 않는다.
                return;
            }
            String decisionKey = checkId == null ? key : CHECK_KEY_PREFIX + checkId;
            grouped.computeIfAbsent(decisionKey, ignored -> new ArrayList<>()).add(subject(key, history));
        });

        List<DecisionRecord> records = new ArrayList<>();
        situations.values().stream()
                .sorted(Comparator.comparing(ProactiveCheck::id))
                .forEach(check -> records.add(checkRecord(
                        check,
                        candidates.getOrDefault(check.id(), List.of()),
                        judgments.getOrDefault(check.id(), List.of()),
                        policies.getOrDefault(check.id(), List.of()),
                        grouped.getOrDefault(CHECK_KEY_PREFIX + check.id(), List.of()))));
        grouped.forEach((decisionKey, list) -> {
            if (!decisionKey.startsWith(CHECK_KEY_PREFIX)) {
                records.add(new DecisionRecord(decisionKey, null, List.of(), List.of(), List.of(), list));
            }
        });
        return new DecisionFeedbackExport(
                DecisionFeedbackExport.VERSION, FeedbackLabeler.VERSION, from, to, List.copyOf(records));
    }

    private static DecisionRecord checkRecord(
            ProactiveCheck check,
            List<ProactiveCheckProblem> candidates,
            List<ValueEvaluation> judgments,
            List<AutonomyDecision> policies,
            List<Subject> subjects) {
        return new DecisionRecord(
                CHECK_KEY_PREFIX + check.id(),
                situation(check),
                candidates.stream().map(DecisionFeedbackExporter::candidate).toList(),
                judgments.stream().map(DecisionFeedbackExporter::judgment).toList(),
                policies.stream().map(DecisionFeedbackExporter::policy).toList(),
                subjects);
    }

    /**
     * 그 기간에 사건이 있는 제안의 사건 전부를 제안별로 묶는다. 사건 하나라도 지운 대화나 남의 대화에 묶였으면 그 제안을 뺀다.
     *
     * <p>기간 앞의 사건도 함께 읽어 첫 반응을 잃지 않게 한다.
     */
    private Map<String, List<FeedbackEvent>> visibleSubjects(CurrentUser user, Instant from) {
        Set<String> keys =
                events.findByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAscIdAsc(user.id(), from).stream()
                        .map(FeedbackEvent::subjectKey)
                        .collect(Collectors.toCollection(HashSet::new));
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<FeedbackEvent> history = events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(user.id(), keys);
        Set<Long> conversationIds = history.stream()
                .map(FeedbackEvent::conversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> active = conversations.activeOf(user, conversationIds).keySet();
        Set<String> hidden = history.stream()
                .filter(event -> event.conversationId() != null && !active.contains(event.conversationId()))
                .map(FeedbackEvent::subjectKey)
                .collect(Collectors.toSet());
        Map<String, List<FeedbackEvent>> bySubject = new LinkedHashMap<>();
        history.stream()
                .filter(event -> !hidden.contains(event.subjectKey()))
                .forEach(event -> bySubject
                        .computeIfAbsent(event.subjectKey(), ignored -> new ArrayList<>())
                        .add(event));
        return bySubject;
    }

    /**
     * 제안마다 묶을 살펴보기 번호다. 사건이 가리키는 살펴보기가 먼저이고, 없으면 제안을 낸 실행 트리의 살펴보기다. 자동 실행한 살펴보기는 그
     * 실행을 허락한 판정의 원천 살펴보기로 바꾼다.
     */
    private Map<String, Long> subjectChecks(CurrentUser user, Map<String, List<FeedbackEvent>> subjects) {
        Set<Long> roots = subjects.values().stream()
                .flatMap(List::stream)
                .map(FeedbackEvent::originExecutionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProactiveCheck> byRoot = roots.isEmpty()
                ? Map.of()
                : checks.findByUserIdAndRootExecutionIdIn(user.id(), roots).stream()
                        .collect(Collectors.toMap(ProactiveCheck::rootExecutionId, Function.identity()));
        Map<String, Long> resolved = new HashMap<>();
        subjects.forEach((key, history) -> history.stream()
                .map(event -> event.sourceCheckId() != null
                        ? event.sourceCheckId()
                        : event.originExecutionId() == null
                                ? null
                                : byRoot.get(event.originExecutionId()) == null
                                        ? null
                                        : byRoot.get(event.originExecutionId()).id())
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(checkId -> resolved.put(key, checkId)));
        Set<Long> autonomous = resolved.entrySet().stream()
                .filter(entry -> FeedbackSubjectType.ofKey(entry.getKey()) == FeedbackSubjectType.CHECK)
                .map(Map.Entry::getValue)
                .collect(Collectors.toSet());
        if (!autonomous.isEmpty()) {
            Map<Long, Long> sources = decisions.findByUserIdAndExecutionCheckIdIn(user.id(), autonomous).stream()
                    .collect(Collectors.toMap(
                            AutonomyDecision::executionCheckId,
                            AutonomyDecision::sourceCheckId,
                            (first, ignored) -> first));
            resolved.replaceAll((key, checkId) -> FeedbackSubjectType.ofKey(key) == FeedbackSubjectType.CHECK
                    ? sources.getOrDefault(checkId, checkId)
                    : checkId);
        }
        return resolved;
    }

    /** 요청자의 살펴보기 가운데 점검 대화를 지우지 않은 것이다. */
    private Map<Long, ProactiveCheck> activeChecks(CurrentUser user, Set<Long> checkIds) {
        if (checkIds.isEmpty()) {
            return Map.of();
        }
        List<ProactiveCheck> owned = checks.findByUserIdAndIdIn(user.id(), checkIds);
        Set<Long> active = conversations
                .activeOf(
                        user, owned.stream().map(ProactiveCheck::conversationId).collect(Collectors.toSet()))
                .keySet();
        Map<Long, ProactiveCheck> result = new TreeMap<>();
        owned.stream()
                .filter(check -> active.contains(check.conversationId()))
                .forEach(check -> result.put(check.id(), check));
        return result;
    }

    private static Situation situation(ProactiveCheck check) {
        return new Situation(
                check.id(),
                check.agentId(),
                check.trigger(),
                check.status(),
                check.outcome(),
                check.trigger() != CheckTrigger.AUTONOMY && check.report() != null,
                check.startedAt(),
                check.finishedAt());
    }

    private static Candidate candidate(ProactiveCheckProblem row) {
        return new Candidate(
                row.id(),
                row.status(),
                row.dropReason(),
                row.problemKey(),
                row.actionType(),
                row.sideEffect(),
                row.confidence(),
                row.evidence() == null
                        ? List.of()
                        : row.evidence().stream()
                                .map(ProblemEvidence::topicKey)
                                .filter(Objects::nonNull)
                                .toList(),
                row.evidenceCheckedAt());
    }

    private static Judgment judgment(ValueEvaluation evaluation) {
        DecisionEvidence evidence = evaluation.evidence();
        return new Judgment(
                evaluation.id(),
                evaluation.replayOfId(),
                evaluation.outcome(),
                evidence.state().version(),
                evidence.state().asOf(),
                evaluation.createdAt(),
                evidence.provider() == null ? null : evidence.provider().adapter(),
                evidence.provider() == null ? null : evidence.provider().version(),
                evidence.provider() == null ? null : evidence.provider().requestedModel(),
                evidence.provider() == null ? null : evidence.provider().actualModel(),
                evidence.result() == null ? List.of() : evidence.result().orderedCandidateIds(),
                evidence.result() == null
                        ? List.of()
                        : evidence.result().judgements().stream()
                                .map(DecisionFeedbackExporter::axes)
                                .toList());
    }

    private static CandidateAxes axes(CandidateJudgement judgement) {
        Map<DecisionAxis, DecisionLevel> choices = new EnumMap<>(DecisionAxis.class);
        Map<DecisionAxis, DecisionConfidence> confidences = new EnumMap<>(DecisionAxis.class);
        for (AxisJudgement axis : judgement.axes()) {
            choices.putIfAbsent(axis.axis(), axis.choice());
            confidences.putIfAbsent(axis.axis(), axis.confidence());
        }
        return new CandidateAxes(judgement.candidateId(), choices, confidences, judgement.confidence());
    }

    private static Policy policy(AutonomyDecision decision) {
        return new Policy(
                decision.id(),
                decision.evaluationId(),
                decision.candidateId(),
                decision.level(),
                decision.reasons(),
                decision.policyVersion(),
                decision.executionStatus(),
                decision.executionCheckId(),
                decision.createdAt());
    }

    private static Subject subject(String key, List<FeedbackEvent> history) {
        FeedbackSubjectType type = history.getFirst().subjectType();
        SubjectLabel label = FeedbackLabeler.label(type, history);
        return new Subject(
                key,
                type,
                history.stream()
                        .map(event -> new Event(
                                event.eventType(),
                                event.actor(),
                                event.occurredAt(),
                                event.subjectVersion(),
                                event.reasonCode(),
                                event.changedFieldList()))
                        .toList(),
                label.label(),
                label.wantsNow(),
                label.edited(),
                label.outcome(),
                label.persistentPreference());
    }
}
