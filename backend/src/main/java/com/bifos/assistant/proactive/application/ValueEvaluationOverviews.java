package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.application.model.EvaluationOverview;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면이 읽는 가치 평가 묶음을 만든다. 평가와 판정은 기존 서비스를 그대로 부르고 검사와 기록을 새로 만들지 않는다.
 *
 * <p>트랜잭션을 길게 열지 않는다. 평가와 판정이 각자 짧은 트랜잭션을 쓰고, 모델을 기다리는 동안 연결을 쥐지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ValueEvaluationOverviews {

    private final AgentService agents;
    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final ValueEvaluationRepository evaluations;
    private final AutonomyDecisionRepository decisions;
    private final ValueEvaluationService evaluationService;
    private final AutonomyPolicyService autonomy;

    /**
     * 요청자가 그 에이전트로 연, 받아들인 후보가 있는 마지막 살펴보기와 그 마지막 평가와 판정을 읽는다.
     *
     * @throws ApiException {@code AGENT_NOT_FOUND}, {@code AGENT_DISABLED}
     */
    @Transactional(readOnly = true)
    public EvaluationOverview latest(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        List<ProactiveCheck> found = checks.findEvaluable(user.id(), agent.id(), PageRequest.of(0, 1));
        if (found.isEmpty()) {
            return EvaluationOverview.none();
        }
        ProactiveCheck check = found.getFirst();
        ValueEvaluation evaluation = evaluations
                .findFirstByUserIdAndCheckIdOrderByIdDesc(user.id(), check.id())
                .orElse(null);
        List<AutonomyDecision> latestDecisions = evaluation == null
                ? List.of()
                : lastBatch(decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(user.id(), List.of(evaluation.id())));
        return new EvaluationOverview(check, acceptedCandidates(check), evaluation, latestDecisions);
    }

    /**
     * 요청자의 끝난 살펴보기를 평가하고 곧바로 판정해 묶음으로 돌려준다. 판정이 실패해도 평가 줄은 남는다.
     *
     * @throws ApiException {@code VALUE_EVALUATION_NOT_FOUND}, {@code VALUE_EVALUATION_STATE_CONFLICT}
     */
    public EvaluationOverview run(CurrentUser user, Long checkId, String providerId) {
        ValueEvaluation evaluation = evaluationService.evaluate(user, checkId, providerId);
        List<AutonomyDecision> decided = autonomy.decide(user, evaluation.id());
        ProactiveCheck check = checks.findByIdAndUserId(checkId, user.id())
                .orElseThrow(
                        () -> new ApiException(ErrorCode.VALUE_EVALUATION_NOT_FOUND, "value evaluation not found"));
        return new EvaluationOverview(check, acceptedCandidates(check), evaluation, decided);
    }

    private int acceptedCandidates(ProactiveCheck check) {
        return problems.findByCheckIdAndStatusOrderByIdAsc(check.id(), ProblemStatus.ACCEPTED)
                .size();
    }

    /** 한 번 판정할 때 남긴 줄은 모두 같은 {@code createdAt} 이다. 가장 늦은 시각의 줄만 남긴다. */
    private static List<AutonomyDecision> lastBatch(Collection<AutonomyDecision> rows) {
        Instant last = rows.stream()
                .map(AutonomyDecision::createdAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return rows.stream().filter(row -> row.createdAt().equals(last)).toList();
    }
}
