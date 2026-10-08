package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 판단 provider 가 준비되지 않았으면 평가는 FALLBACK, 판정은 모두 IGNORE 이고 시도는 그래도 DECIDED 다. */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-unavailable"})
class ProactiveLoopFallbackTest extends ProactiveLoopTestSupport {

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Test
    @DisplayName("provider 를 쓸 수 없으면 시도는 DECIDED, 평가는 FALLBACK, 판정은 모두 IGNORE 이고 알림은 늘지 않는다")
    void decidesWithFallbackWhenProviderUnavailable() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, candidateOutput());

        List<ValueEvaluation> judged = evaluations.findByUserIdAndCheckIdInOrderByIdAsc(user.id(), List.of(checkId));
        assertThat(judged).as("평가 줄").hasSize(1);
        ValueEvaluation evaluation = judged.getFirst();
        assertThat(evaluation.outcome()).as("평가 결과").isEqualTo(DecisionOutcome.FALLBACK);
        List<AutonomyDecision> decided =
                decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(user.id(), List.of(evaluation.id()));
        assertThat(decided).as("판정 줄").isNotEmpty();
        assertThat(decided).extracting(AutonomyDecision::level).containsOnly(AutonomyLevel.IGNORE);
        ProactiveLoopRun run = loopRuns.findBySourceCheckId(checkId).orElseThrow();
        assertThat(run.status()).as("시도 상태").isEqualTo(LoopRunStatus.DECIDED);
        assertThat(run.evaluationId()).as("시도의 평가 번호").isEqualTo(evaluation.id());
        assertThat(notificationCount(user)).as("알림 수").isZero();
    }
}
