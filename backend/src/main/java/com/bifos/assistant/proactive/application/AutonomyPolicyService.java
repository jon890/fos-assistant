package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.application.model.AutonomyVerdict;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.AutonomyPreference;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.AutonomyPreferenceRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 요청자의 가치 평가를 행동 정책으로 판정하고 남긴다(ADR-20261007 autonomy-policy). {@code EXECUTE} 일 때만 읽기 전용 살펴보기를 한 번
 * 시작한다.
 *
 * <p>판정과 실행 키는 한 트랜잭션에 저장하고, 커밋한 뒤 트랜잭션 밖에서 시작한다. 시작은 기존 살펴보기 경로의 검사를 모두 다시 거친다. 순서와
 * 실패 처리는 {@code docs/backend/autonomy-policy.md} 의 「자동 실행」 이 갖는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomyPolicyService {

    private final ValueEvaluationStore evaluations;
    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final AutonomyDecisionRepository decisions;
    private final AutonomyPreferenceRepository preferences;
    private final AgentService agents;
    private final ProactiveCheckService checkService;
    private final AutonomyProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 평가의 후보마다 판정을 남기고, {@code EXECUTE} 가 있으면 시작한다.
     *
     * @throws ApiException {@code VALUE_EVALUATION_NOT_FOUND}, {@code VALUE_EVALUATION_STATE_CONFLICT}
     */
    public List<AutonomyDecision> decide(CurrentUser user, Long evaluationId) {
        Decided decided;
        try {
            decided = transactions.execute(status -> record(user, evaluationId));
        } catch (DataIntegrityViolationException ex) {
            // 같은 원천의 실행 키를 다른 요청이 먼저 저장했다. 다시 판정하면 ALREADY_EXECUTED 가 붙는다.
            log.info("실행 키가 겹쳐 행동 정책을 다시 판정한다 evaluationId={}", evaluationId);
            decided = transactions.execute(status -> record(user, evaluationId));
        }
        List<AutonomyDecision> rows = new ArrayList<>(decided.rows());
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).level() == AutonomyLevel.EXECUTE) {
                rows.set(i, execute(user, decided.agentCode(), rows.get(i)));
            }
        }
        return List.copyOf(rows);
    }

    public boolean readOnlyExecutionConsented(CurrentUser user) {
        return preferences
                .findById(user.id())
                .map(AutonomyPreference::readOnlyExecution)
                .orElse(false);
    }

    public boolean changeReadOnlyExecution(CurrentUser user, boolean consented) {
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            AutonomyPreference row = preferences
                    .findById(user.id())
                    .map(existing -> {
                        existing.change(consented, now);
                        return existing;
                    })
                    .orElseGet(() -> AutonomyPreference.of(user.id(), consented, now));
            return preferences.save(row).readOnlyExecution();
        }));
    }

    private Decided record(CurrentUser user, Long evaluationId) {
        ValueEvaluation evaluation = evaluations.read(user.id(), evaluationId);
        if (evaluation.outcome() == DecisionOutcome.RUNNING) {
            throw new ApiException(ErrorCode.VALUE_EVALUATION_STATE_CONFLICT, "value evaluation is still running");
        }
        ProactiveCheck source = checks.findByIdAndUserId(evaluation.checkId(), user.id())
                .orElseThrow(
                        () -> new ApiException(ErrorCode.VALUE_EVALUATION_NOT_FOUND, "value evaluation not found"));
        Optional<Agent> agent = agents.findById(source.agentId());
        Facts facts = facts(user, source, agent);
        DecisionEvidence evidence = evaluation.evidence();
        Map<Long, CandidateJudgement> judgements = evidence.result().judgements().stream()
                .collect(Collectors.toMap(
                        CandidateJudgement::candidateId, Function.identity(), (first, ignored) -> first));
        List<DecisionCandidate> ordered = ordered(evidence);
        List<AutonomyInputs> inputs = ordered.stream()
                .map(candidate -> inputsOf(evaluation, candidate, judgements.get(candidate.candidateId()), facts))
                .toList();
        List<AutonomyVerdict> verdicts =
                AutonomyPolicy.decideAll(inputs, properties.maxEvaluationAge(), properties.maxEvidenceAge());
        List<AutonomyDecision> rows = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            AutonomyVerdict verdict = verdicts.get(i);
            rows.add(AutonomyDecision.of(
                    user.id(),
                    evaluation.id(),
                    ordered.get(i).candidateId(),
                    source.id(),
                    verdict.level(),
                    verdict.reasons(),
                    inputs.get(i),
                    AutonomyPolicy.VERSION,
                    facts.now()));
        }
        return new Decided(
                decisions.saveAllAndFlush(rows), agent.map(Agent::code).orElse(null));
    }

    /** 후보와 상관없이 판정 하나에 같은 값이다. 허락에 해당하는 값은 모두 여기서 Control Plane 의 기록으로 읽는다. */
    private Facts facts(CurrentUser user, ProactiveCheck source, Optional<Agent> agent) {
        return new Facts(
                clock.instant(),
                readOnlyExecutionConsented(user),
                agent.map(Agent::proactiveCheckWritesAllowed).orElse(false),
                source.trigger(),
                agent.filter(each -> !each.isDeleted() && each.enabled() && each.isReadableBy(user.id()))
                        .isPresent(),
                decisions.existsByExecutionKey(AutonomyDecision.executionKey(source.id())));
    }

    private AutonomyInputs inputsOf(
            ValueEvaluation evaluation, DecisionCandidate candidate, CandidateJudgement judgement, Facts facts) {
        return new AutonomyInputs(
                evaluation.outcome(),
                evaluation.replayOfId() != null,
                evaluation.createdAt(),
                evaluation.evidence().state().asOf(),
                facts.now(),
                judgement != null,
                current(evaluation.checkId(), candidate),
                candidate.actionType(),
                candidate.sideEffect(),
                candidate.confidence(),
                choices(judgement),
                axisConfidences(judgement),
                judgement == null ? null : judgement.confidence(),
                candidate.evidenceCheckedAt(),
                properties.executionEnabled(),
                facts.consented(),
                facts.writesAllowed(),
                facts.sourceTrigger(),
                facts.startable(),
                facts.alreadyExecuted());
    }

    /**
     * 평가가 순서를 냈으면 그 순서로, 아니면 후보 식별자 순서로 둔다. 순서에 없는 후보는 뒤에 식별자 순서로 붙인다. 모델이 낸 순서는 어느 후보가
     * 먼저 실행 자리를 갖는지만 정한다.
     */
    private static List<DecisionCandidate> ordered(DecisionEvidence evidence) {
        Map<Long, DecisionCandidate> byId = new LinkedHashMap<>();
        evidence.state().candidates().stream()
                .sorted(Comparator.comparing(DecisionCandidate::candidateId))
                .forEach(candidate -> byId.putIfAbsent(candidate.candidateId(), candidate));
        List<DecisionCandidate> ordered = new ArrayList<>();
        if (evidence.result().outcome() == DecisionOutcome.EVALUATED) {
            for (Long id : evidence.result().orderedCandidateIds()) {
                DecisionCandidate candidate = byId.remove(id);
                if (candidate != null) {
                    ordered.add(candidate);
                }
            }
        }
        ordered.addAll(byId.values());
        return ordered;
    }

    /** 지금의 후보 줄이 같은 살펴보기의 받아들인 줄이고, 판정에 쓰는 칸이 스냅샷과 같은가. */
    private boolean current(Long checkId, DecisionCandidate snapshot) {
        return problems.findById(snapshot.candidateId())
                .filter(row -> checkId.equals(row.checkId()))
                .filter(row -> row.status() == ProblemStatus.ACCEPTED)
                .filter(row -> same(row, snapshot))
                .isPresent();
    }

    private static boolean same(ProactiveCheckProblem row, DecisionCandidate snapshot) {
        return Objects.equals(row.problemKey(), snapshot.problemKey())
                && Objects.equals(row.actionType(), snapshot.actionType())
                && Objects.equals(row.actionText(), snapshot.actionText())
                && Objects.equals(row.sideEffect(), snapshot.sideEffect());
    }

    private static Map<DecisionAxis, DecisionLevel> choices(CandidateJudgement judgement) {
        Map<DecisionAxis, DecisionLevel> choices = new EnumMap<>(DecisionAxis.class);
        if (judgement != null) {
            for (AxisJudgement axis : judgement.axes()) {
                choices.putIfAbsent(axis.axis(), axis.choice());
            }
        }
        return choices;
    }

    private static Map<DecisionAxis, DecisionConfidence> axisConfidences(CandidateJudgement judgement) {
        Map<DecisionAxis, DecisionConfidence> confidences = new EnumMap<>(DecisionAxis.class);
        if (judgement != null) {
            for (AxisJudgement axis : judgement.axes()) {
                confidences.putIfAbsent(axis.axis(), axis.confidence());
            }
        }
        return confidences;
    }

    /**
     * 커밋한 실행 키로 읽기 전용 살펴보기를 시작하고 결과를 적는다. 실패해도 다시 시작하지 않는다. 결과를 적지 못하면 줄은 {@code PENDING}
     * 으로 남고 그것도 다시 시작하지 않는다.
     */
    private AutonomyDecision execute(CurrentUser user, String agentCode, AutonomyDecision row) {
        AtomicReference<Long> startedCheck = new AtomicReference<>();
        String error = null;
        try {
            checkService.startAutonomous(user, agentCode, check -> startedCheck.set(check.id()));
        } catch (ApiException ex) {
            error = ex.code().name();
        } catch (RuntimeException ex) {
            log.warn("자동 실행을 시작하지 못했다 decisionId={}", row.id(), ex);
            error = ErrorCode.INTERNAL_ERROR.name();
        }
        String failure = error;
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                AutonomyDecision saved = decisions.findById(row.id()).orElseThrow();
                if (failure == null) {
                    saved.started(startedCheck.get());
                } else {
                    saved.failed(failure, startedCheck.get());
                }
                return decisions.save(saved);
            }));
        } catch (RuntimeException ex) {
            log.warn("자동 실행 결과를 적지 못했다 decisionId={}", row.id(), ex);
            return row;
        }
    }

    private record Decided(List<AutonomyDecision> rows, String agentCode) {}

    private record Facts(
            Instant now,
            boolean consented,
            boolean writesAllowed,
            CheckTrigger sourceTrigger,
            boolean startable,
            boolean alreadyExecuted) {}
}
