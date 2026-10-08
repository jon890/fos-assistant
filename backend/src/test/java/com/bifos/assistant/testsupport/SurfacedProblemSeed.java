package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 매일 루프가 판정을 남긴 상태를 모델을 거치지 않고 직접 저장한다. 점검 대화, 살펴보기, 문제 후보, 평가, 판정, {@code DECIDED} 시도 줄을
 * 한 번에 만든다. 모든 값은 합성이다.
 */
public class SurfacedProblemSeed {

    private final CheckConversations checkConversations;
    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final ValueEvaluationRepository evaluations;
    private final AutonomyDecisionRepository decisions;
    private final ProactiveLoopRunRepository loopRuns;

    public SurfacedProblemSeed(
            CheckConversations checkConversations,
            ProactiveCheckRepository checks,
            ProactiveCheckProblemRepository problems,
            ValueEvaluationRepository evaluations,
            AutonomyDecisionRepository decisions,
            ProactiveLoopRunRepository loopRuns) {
        this.checkConversations = checkConversations;
        this.checks = checks;
        this.problems = problems;
        this.evaluations = evaluations;
        this.decisions = decisions;
        this.loopRuns = loopRuns;
    }

    /**
     * @param problemKey 문제 키
     * @param problem 문제 글. 비워 두면 문제 글이 빈 후보다
     * @param action 제안한 다음 행동 글
     * @param at 살펴보기, 판정, 시도 줄을 저장한 시각
     */
    public record Spec(String problemKey, String problem, String action, AutonomyLevel level, Instant at) {}

    /** 저장한 줄의 번호다. */
    public record Seeded(
            Long checkId, Long conversationId, Long problemId, Long evaluationId, Long decisionId, Long runId) {}

    /** 새 살펴보기 하나에 후보 하나와 그 판정 하나를 저장하고 {@code DECIDED} 시도를 잇는다. */
    public Seeded decided(CurrentUser user, Agent agent, Spec spec) {
        Long conversationId =
                checkConversations.findOrCreate(user, agent).conversation().id();
        ProactiveCheck check =
                ProactiveCheck.started(user.id(), agent.id(), conversationId, CheckTrigger.SCHEDULED, false, spec.at());
        check.succeed(CheckOutcome.NOTHING_NEW, 0, 0, null, 0, 0, 0, 0, 0, spec.at());
        Long checkId = checks.save(check).id();
        ProactiveCheckProblem problem = problems.save(ProactiveCheckProblem.of(
                checkId,
                conversationId,
                ProblemStatus.ACCEPTED,
                null,
                spec.problemKey(),
                spec.problem(),
                null,
                "ACTION",
                spec.action(),
                "HIGH",
                null,
                "NONE",
                null,
                null,
                List.of(),
                null,
                spec.at()));
        ValueEvaluation evaluation = evaluations.save(ValueEvaluation.of(
                checkId,
                user.id(),
                null,
                new DecisionEvidence(
                        new DecisionState(1, spec.at(), List.of()),
                        List.of(),
                        new DecisionProviderInfo("fixture", "1", null, "fixture-model", null, null, null),
                        new DecisionResult(DecisionOutcome.EVALUATED, List.of(), List.of(), "합성 평가", null)),
                spec.at()));
        AutonomyDecision decision = decide(user, evaluation.id(), problem.id(), checkId, spec.level(), spec.at());
        ProactiveLoopRun run = ProactiveLoopRun.running(user.id(), checkId, spec.at());
        run.decided(evaluation.id(), spec.at());
        return new Seeded(
                checkId,
                conversationId,
                problem.id(),
                evaluation.id(),
                decision.id(),
                loopRuns.save(run).id());
    }

    /** 같은 평가와 후보를 판정 API 로 다시 판정한 것처럼 늦은 판정 줄을 더한다. */
    public AutonomyDecision redecide(CurrentUser user, Seeded seeded, AutonomyLevel level, Instant at) {
        return decide(user, seeded.evaluationId(), seeded.problemId(), seeded.checkId(), level, at);
    }

    /** 사용자의 이 후보들에 딸린 줄을 외래 키 순서대로 지운다. 사용자와 에이전트와 대화는 부르는 쪽이 지운다. */
    public static void deleteRowsOf(JdbcTemplate jdbc, Long userId) {
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_loop_run WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_autonomy_decision WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_value_evaluation WHERE user_id = ?", userId);
        jdbc.update(
                "DELETE FROM proactive_check_problem WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
    }

    private AutonomyDecision decide(
            CurrentUser user, Long evaluationId, Long candidateId, Long checkId, AutonomyLevel level, Instant at) {
        return decisions.save(AutonomyDecision.of(
                user.id(),
                evaluationId,
                candidateId,
                checkId,
                level,
                List.of(AutonomyReason.READ_ONLY_SAFE),
                new AutonomyInputs(
                        DecisionOutcome.EVALUATED,
                        false,
                        at,
                        at,
                        at,
                        true,
                        true,
                        "ACTION",
                        "NONE",
                        "HIGH",
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        false,
                        false,
                        false,
                        CheckTrigger.SCHEDULED,
                        true,
                        false),
                1,
                at));
    }
}
