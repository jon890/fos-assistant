package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.proactive.application.DecisionFeedbackExporter;
import com.bifos.assistant.proactive.application.ProactiveCheckSettled;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.DecisionRecord;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Loop;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.domain.type.LoopSkippedReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** 설치가 매일 루프를 연 상태에서 매일 깨우기 뒤 이음매가 줄을 남기는 조건, 건너뛰는 순서, 원천 유일, 하루 상한을 본다. */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-a"})
class ProactiveLoopCoordinatorTest extends ProactiveLoopTestSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    DecisionFeedbackExporter exporter;

    @Test
    @DisplayName("동의한 사용자의 매일 깨우기 하나에 시도 줄 하나가 DECIDED 이고 평가 하나와 후보별 판정이 남으며 알림은 늘지 않는다")
    void decidesOnceForConsentedScheduledCheck() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, candidateOutput());

        ProactiveCheck check = checks.findById(checkId).orElseThrow();
        assertThat(check.status()).as("살펴보기 상태").isEqualTo(CheckStatus.SUCCEEDED);
        List<ProactiveCheckProblem> accepted =
                problems.findByCheckIdAndStatusOrderByIdAsc(checkId, ProblemStatus.ACCEPTED);
        assertThat(accepted).as("받아들인 문제 후보").hasSize(1);
        List<ValueEvaluation> judged = evaluations.findByUserIdAndCheckIdInOrderByIdAsc(user.id(), List.of(checkId));
        assertThat(judged).as("평가 줄").hasSize(1);
        ValueEvaluation evaluation = judged.getFirst();
        assertThat(evaluation.outcome()).as("평가 결과").isEqualTo(DecisionOutcome.EVALUATED);
        List<AutonomyDecision> decided =
                decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(user.id(), List.of(evaluation.id()));
        assertThat(decided)
                .as("후보마다 판정 한 줄")
                .extracting(AutonomyDecision::candidateId)
                .containsExactly(accepted.getFirst().id());

        List<ProactiveLoopRun> runs = runsOf(user);
        assertThat(runs).as("시도 줄").hasSize(1);
        ProactiveLoopRun run = runs.getFirst();
        assertThat(run.sourceCheckId()).as("원천 살펴보기").isEqualTo(checkId);
        assertThat(run.status()).as("시도 상태").isEqualTo(LoopRunStatus.DECIDED);
        assertThat(run.evaluationId()).as("시도의 평가 번호").isEqualTo(evaluation.id());
        assertThat(run.skippedReason()).as("건너뛴 까닭").isNull();
        assertThat(run.errorCode()).as("오류 코드").isNull();
        assertThat(run.createdAt()).as("저장 시각").isEqualTo(BASE);
        assertThat(run.finishedAt()).as("끝난 시각").isEqualTo(BASE);
        assertThat(notificationCount(user)).as("알림 수").isZero();

        DecisionRecord record = exporter.export(user, Duration.ofDays(1)).records().stream()
                .filter(each -> each.decisionKey().equals("check:" + checkId))
                .findFirst()
                .orElseThrow();
        Loop loop = record.situation().loop();
        assertThat(loop).as("export 의 시도").isNotNull();
        assertThat(loop.runId()).isEqualTo(run.id());
        assertThat(loop.status()).isEqualTo(LoopRunStatus.DECIDED);
        assertThat(loop.evaluationId()).isEqualTo(evaluation.id());
    }

    @Test
    @DisplayName("같은 살펴보기의 끝 사건을 다시 내도 시도, 평가, 판정 수가 늘지 않는다")
    void ignoresRepeatedSettledEventForSameCheck() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);
        Long checkId = wake(user, agent, candidateOutput());
        long evaluationsBefore = evaluationCount(user);
        long decisionsBefore = decisionCount(user);
        assertThat(runsOf(user)).as("첫 시도").hasSize(1);

        events.publishEvent(new ProactiveCheckSettled(user, checkId));
        awaitIdle();

        assertThat(runsOf(user)).as("다시 낸 뒤의 시도 줄").hasSize(1);
        assertThat(runsOf(user).getFirst().status()).isEqualTo(LoopRunStatus.DECIDED);
        assertThat(evaluationCount(user)).as("평가 수").isEqualTo(evaluationsBefore);
        assertThat(decisionCount(user)).as("판정 수").isEqualTo(decisionsBefore);
    }

    @Test
    @DisplayName("설정 줄이 없으면 시도 줄과 평가가 없다")
    void leavesNoRunWithoutSetting() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);

        wake(user, agent, candidateOutput());

        assertThat(runsOf(user)).as("시도 줄").isEmpty();
        assertThat(evaluationCount(user)).as("평가 수").isZero();
    }

    @Test
    @DisplayName("설정 줄이 꺼져 있으면 시도 줄과 평가가 없다")
    void leavesNoRunWhenSettingDisabled() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, false, null);

        wake(user, agent, candidateOutput());

        assertThat(runsOf(user)).as("시도 줄").isEmpty();
        assertThat(evaluationCount(user)).as("평가 수").isZero();
    }

    @Test
    @DisplayName("단추로 연 살펴보기는 동의했어도 시도 줄과 평가가 없다")
    void leavesNoRunForManualCheck() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        startManual(user, agent, candidateOutput());

        assertThat(runsOf(user)).as("시도 줄").isEmpty();
        assertThat(evaluationCount(user)).as("평가 수").isZero();
    }

    @Test
    @DisplayName("쉬는 중이면 후보가 있어도 SKIPPED/SNOOZED 이고 평가가 없다")
    void skipsWhileSnoozed() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, BASE.plus(Duration.ofHours(1)));

        Long checkId = wake(user, agent, candidateOutput());

        assertSkipped(user, checkId, LoopSkippedReason.SNOOZED);
    }

    @Test
    @DisplayName("대역 답이 NOTHING_NEW 면 SKIPPED/NO_CANDIDATE 이고 평가가 없다")
    void skipsWithoutAcceptedCandidate() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, nothingNewOutput());

        assertThat(checks.findById(checkId).orElseThrow().outcome())
                .as("살펴보기 결과")
                .isEqualTo(CheckOutcome.NOTHING_NEW);
        assertSkipped(user, checkId, LoopSkippedReason.NO_CANDIDATE);
    }

    @Test
    @DisplayName("19시간 전에 판정까지 간 시도가 있으면 SKIPPED/DAILY_LIMIT 이고 평가가 없다")
    void skipsAtDailyLimitWithinTwentyHours() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);
        saveEarlierDecidedRun(user, agent, BASE.minus(Duration.ofHours(19)));

        Long checkId = wake(user, agent, candidateOutput());

        assertSkipped(user, checkId, LoopSkippedReason.DAILY_LIMIT);
    }

    @Test
    @DisplayName("앞선 시도가 23시간 전이면 어제보다 일찍 끝난 오늘 깨우기도 DECIDED 다")
    void decidesWhenEarlierRunIsOutsideTwentyHours() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);
        saveEarlierDecidedRun(user, agent, BASE.minus(Duration.ofHours(23)));

        Long checkId = wake(user, agent, candidateOutput());

        ProactiveLoopRun run = loopRuns.findBySourceCheckId(checkId).orElseThrow();
        assertThat(run.status()).as("오늘 시도 상태").isEqualTo(LoopRunStatus.DECIDED);
        assertThat(evaluationCount(user)).as("평가 수").isEqualTo(1);
    }

    @Test
    @DisplayName("19시간 안에 건너뛴 시도만 있으면 하루 상한에 세지 않아 DECIDED 다")
    void ignoresSkippedRunsForDailyLimit() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);
        saveEarlierSkippedRun(user, agent, BASE.minus(Duration.ofHours(19)), LoopSkippedReason.NO_CANDIDATE);

        Long checkId = wake(user, agent, candidateOutput());

        ProactiveLoopRun run = loopRuns.findBySourceCheckId(checkId).orElseThrow();
        assertThat(run.status()).as("오늘 시도 상태").isEqualTo(LoopRunStatus.DECIDED);
        assertThat(evaluationCount(user)).as("평가 수").isEqualTo(1);
    }

    @Test
    @DisplayName("하루 상한에 닿았어도 받아들인 후보가 없으면 NO_CANDIDATE 가 먼저다")
    void prefersNoCandidateOverDailyLimit() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);
        saveEarlierDecidedRun(user, agent, BASE.minus(Duration.ofHours(19)));

        Long checkId = wake(user, agent, nothingNewOutput());

        assertSkipped(user, checkId, LoopSkippedReason.NO_CANDIDATE);
    }

    private void assertSkipped(CurrentUser user, Long checkId, LoopSkippedReason reason) {
        ProactiveLoopRun run = loopRuns.findBySourceCheckId(checkId).orElseThrow();
        assertThat(run.status()).as("시도 상태").isEqualTo(LoopRunStatus.SKIPPED);
        assertThat(run.skippedReason()).as("건너뛴 까닭").isEqualTo(reason);
        assertThat(run.evaluationId()).as("평가 번호").isNull();
        assertThat(run.finishedAt()).as("끝난 시각").isEqualTo(BASE);
        assertThat(evaluationCount(user)).as("평가 수").isZero();
        assertThat(decisionCount(user)).as("판정 수").isZero();
    }
}
