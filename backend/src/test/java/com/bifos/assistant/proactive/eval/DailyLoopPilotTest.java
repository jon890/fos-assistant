package com.bifos.assistant.proactive.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.application.DecisionFeedbackExporter;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.application.ProactiveCheckSettled;
import com.bifos.assistant.proactive.application.ProactiveLoopSettingService;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.DecisionRecord;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Loop;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.domain.type.LoopSkippedReason;
import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.eval.EvalDataset.Truth;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.application.TaskDispatcher;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 매일 깨우기와 매일 루프를 결정적 provider 와 대역 Hermes 로 7일 이어 돌려, 중요한 문제의 적중, 중복, 유용한 침묵, 호출 수와 흉내 비용을
 * 세고 안전 경계를 확인한다(ADR-20261008 daily-loop).
 *
 * <p>하루마다 {@link TaskDispatcher#tick} 으로 예약 작업을 깨우고, 대역 Hermes 가 그날의 버전 3 결과 블록을 답한다. 후보와 판단 기록은
 * {@code scenarios.json} 의 것을 그대로 쓴다. 품질 지표는 보고서에만 남기고 기준값을 걸지 않는다. 단언하는 것은 안전 경계와 멱등이다.
 * 모든 값은 합성이고 실제 모델과 Hermes 는 부르지 않는다.
 *
 * <p>설치 설정은 시험 클래스 단위로만 바꿀 수 있어 한 시험 안에서 provider 를 바꾸지 않는다. provider 실패는 별도 시험이 본다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({
    "assistant.proactive-loop.enabled=true",
    "assistant.proactive-loop.provider=fixture-a",
    "assistant.autonomy.execution-enabled=true"
})
class DailyLoopPilotTest {

    private static final EvalDataset DATASET = EvalDataset.load();
    private static final String PROVIDER = "fixture-a";
    private static final Path REPORT_DIR = Path.of("build", "reports", "proactive-loop");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final Duration IDLE_LIMIT = Duration.ofSeconds(20);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 깨우기를 켜는 때다. 서울 시각 11월 1일 09:00 이라 첫 발화는 11월 2일 08:30 이다. */
    private static final Instant ENABLED_AT = Instant.parse("2026-11-01T00:00:00Z");

    private static final Instant FIRST_WAKE = Instant.parse("2026-11-01T23:30:00Z");

    private static final String DEADLINE = "career:deadline-tomorrow";
    private static final String BACKLOG = "study:reading-backlog";
    private static final String FLASH_SALE = "event:flash-sale-ending";
    private static final String SUBMIT = "career:submit-application";
    private static final String NOTHING_NEW =
            "블록 밖의 글\n<fos-check-result>\n{\"version\":3,\"outcome\":\"NOTHING_NEW\"}\n</fos-check-result>";
    private static final String DEADLINE_CHANGE = "합성 변화: 마감이 오늘 밤으로 앞당겨졌다";

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    ProactiveScheduleService schedules;

    @Autowired
    ProactiveLoopSettingService loopSettings;

    @Autowired
    AutonomyPolicyService autonomy;

    @Autowired
    ProactiveCheckService checkService;

    @Autowired
    DecisionFeedbackExporter exporter;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    @Autowired
    List<ReplayDecisionProvider> providers;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TestClock clock;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ProactiveLoopRunRepository loopRuns;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    HermesSkillClient skillClient;

    @Autowired
    AgentConnectorBindings connectorBindings;

    private CurrentUser owner;
    private Agent agent;
    private ReplayDecisionProvider provider;

    @BeforeEach
    void setUp() {
        clock.set(ENABLED_AT);
        stub().reset();
        cleanTasks();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        doReturn(false).when(connectorBindings).hasBindings(any());
        doReturn(Set.of()).when(connectorBindings).connectorServers(any());
        doReturn(Set.of()).when(connectorBindings).connectorToolPrefixes(any());
        provider = providers.stream()
                .filter(each -> each.id().equals(PROVIDER))
                .findFirst()
                .orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = users.save(
                AppUser.of("daily-loop-" + suffix + "@example.com", "사용자A", 1L, UserRole.MEMBER, ENABLED_AT));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "career-" + suffix;
        agent = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                ENABLED_AT));
    }

    @AfterEach
    void tearDown() {
        cleanTasks();
        if (owner == null) {
            return;
        }
        Long userId = owner.id();
        jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notification WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM connector_action WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_loop_run WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_loop_setting WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_autonomy_decision WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_autonomy_preference WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM proactive_value_evaluation WHERE user_id = ?", userId);
        jdbc.update(
                "DELETE FROM proactive_check_problem WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
        jdbc.update(
                "DELETE FROM proactive_check_finding WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
        jdbc.update(
                "DELETE FROM execution_event WHERE execution_id IN (SELECT id FROM agent_execution WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
        jdbc.update(
                "DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    @DisplayName("7일 동안 깨우기와 매일 루프를 이어 돌려도 승인 우회와 중복 실행이 없고 같은 사건을 다시 내도 늘지 않는다")
    void runsSevenDaysAndKeepsSafetyBoundaries() throws InterruptedException {
        EvalDataset.Check urgent = DATASET.scenario("urgent-high-value").check();
        EvalDataset.Check lowValue = DATASET.scenario("urgent-low-value").check();
        EvalDataset.Check unsafe = DATASET.scenario("high-value-unsafe").check();
        assertThat(schedules
                        .update(owner, agent.code(), true, "08:30", SEOUL.getId())
                        .nextRunAt())
                .as("첫 발화 예정 시각")
                .isEqualTo(FIRST_WAKE);
        loopSettings.update(owner, agent.code(), true, null);

        List<DayResult> days = new ArrayList<>();
        days.add(runDay(1, "중요한 후보 둘", () -> output(urgent, Set.of(DEADLINE, BACKLOG), Map.of()), false));
        days.add(runDay(2, "같은 후보의 되풀이", () -> output(urgent, Set.of(DEADLINE), Map.of()), false));
        days.add(runDay(3, "새 것 없음", () -> NOTHING_NEW, false));
        days.add(runDay(4, "긴급하지만 낮은 가치", () -> output(lowValue, Set.of(FLASH_SALE), Map.of()), false));
        days.add(runDay(5, "외부 쓰기가 필요한 후보", () -> output(unsafe, Set.of(SUBMIT), Map.of()), false));

        // 6일: 사용자가 읽기 전용 자동 실행에 동의하고, 같은 후보가 바뀐 점을 달고 다시 나온다.
        autonomy.changeReadOnlyExecution(owner, true);
        days.add(runDay(
                6,
                "동의 뒤 바뀐 점이 있는 후보",
                () -> output(urgent, Set.of(DEADLINE), Map.of(DEADLINE, DEADLINE_CHANGE)),
                true));
        ProactiveCheck sixth = checks.findById(days.get(5).checkId()).orElseThrow();
        Counts beforeRepublish = counts();
        events.publishEvent(new ProactiveCheckSettled(owner, sixth.id()));
        backgroundTasks.awaitIdle(IDLE_LIMIT);
        Counts afterRepublish = counts();

        // 7일: 루프 설정을 다음 날까지 쉬기로 둔다.
        Instant seventhWake = wakeAt(7);
        loopSettings.update(owner, agent.code(), true, seventhWake.plus(Duration.ofDays(1)));
        days.add(runDay(7, "쉬는 날", () -> output(urgent, Set.of(DEADLINE), Map.of(DEADLINE, DEADLINE_CHANGE)), true));

        Summary summary = summarize(days);
        writeReport(days, summary);

        assertDay1(days.get(0));
        assertDay2(days.get(1));
        assertDay3(days.get(2));
        assertDay4(days.get(3));
        assertDay5(days.get(4));
        assertDay6(days.get(5), beforeRepublish, afterRepublish);
        assertDay7(days.get(6));
        assertSafetyAcrossDays(days);
        assertThat(summary.failures()).as("이 반복의 실패 시도 수").isZero();
    }

    private void assertDay1(DayResult day) {
        assertThat(day.loopStatus()).as("1일 시도").isEqualTo(LoopRunStatus.DECIDED.name());
        assertThat(day.evaluationOutcome()).isEqualTo(DecisionOutcome.EVALUATED.name());
        assertThat(day.evaluationTotal()).as("1일 뒤 평가 수").isEqualTo(1);
        assertThat(day.levels()).containsOnlyKeys(DEADLINE, BACKLOG);
        assertThat(importantLevels(day)).as("1일 중요한 후보").isNotEmpty().doesNotContain(AutonomyLevel.IGNORE.name());
        assertLevelsAllowed(day, false);
    }

    /** 그날 판정을 받은 후보 가운데 fixture 가 중요하다고 적은 것의 수준이다. */
    private static List<String> importantLevels(DayResult day) {
        return day.levels().entrySet().stream()
                .filter(each -> truthOf(each.getKey()).important())
                .map(Map.Entry::getValue)
                .toList();
    }

    private void assertDay2(DayResult day) {
        assertThat(day.loopStatus()).as("2일 시도").isEqualTo(LoopRunStatus.SKIPPED.name());
        assertThat(day.skippedReason()).isEqualTo(LoopSkippedReason.NO_CANDIDATE.name());
        assertThat(day.droppedKeys())
                .as("문제 찾기가 되풀이를 버린다")
                .containsExactly(DEADLINE + ":" + ProblemDropReason.DUPLICATE);
        assertThat(day.acceptedKeys()).isEmpty();
        assertThat(day.modelCalls()).as("2일 모델 호출").isZero();
        assertThat(day.evaluationTotal()).as("2일 뒤에도 평가는 1").isEqualTo(1);
    }

    private void assertDay3(DayResult day) {
        assertThat(day.loopStatus()).as("3일 시도").isEqualTo(LoopRunStatus.SKIPPED.name());
        assertThat(day.skippedReason()).isEqualTo(LoopSkippedReason.NO_CANDIDATE.name());
        assertThat(day.reportSurfaced()).as("3일 보고").isFalse();
        assertThat(day.notificationDelta()).as("3일 알림 증가").isZero();
        assertThat(day.modelCalls()).as("3일 모델 호출").isZero();
    }

    private void assertDay4(DayResult day) {
        assertThat(day.loopStatus()).as("4일 시도").isEqualTo(LoopRunStatus.DECIDED.name());
        assertThat(day.levels()).containsEntry(FLASH_SALE, AutonomyLevel.IGNORE.name());
        assertThat(day.notificationDelta()).as("4일 알림 증가").isZero();
        assertThat(day.followUpDelta()).as("4일 할 일 증가").isZero();
        assertThat(day.approvalDelta()).as("4일 승인 줄 증가").isZero();
    }

    private void assertDay5(DayResult day) {
        assertThat(day.loopStatus()).as("5일 시도").isEqualTo(LoopRunStatus.DECIDED.name());
        assertThat(day.levels()).containsEntry(SUBMIT, AutonomyLevel.ASK_APPROVAL.name());
        assertThat(day.approvalDelta()).as("5일 승인 줄 증가").isZero();
    }

    private void assertDay6(DayResult day, Counts before, Counts after) {
        assertThat(day.loopStatus()).as("6일 시도").isEqualTo(LoopRunStatus.DECIDED.name());
        assertThat(day.acceptedKeys()).containsExactly(DEADLINE);
        assertThat(day.levels().values().stream().filter(AutonomyLevel.EXECUTE.name()::equals))
                .as("6일 EXECUTE 수")
                .hasSize(1);
        assertLevelsAllowed(day, true);
        assertThat(day.autonomousStarted()).as("6일 자동 실행 살펴보기 수").isEqualTo(1);
        assertThat(before.autonomousStarted()).as("재발행 전 자동 실행 수").isEqualTo(1);
        assertThat(after).as("같은 끝 사건을 다시 낸 뒤의 시도, 평가, 판정, 자동 실행 수").isEqualTo(before);
        List<Long> autonomousChecks = checksOfAgent().stream()
                .filter(check -> check.trigger() == CheckTrigger.AUTONOMY)
                .map(ProactiveCheck::id)
                .toList();
        assertThat(loopRuns.findBySourceCheckIdIn(autonomousChecks))
                .as("자동 실행 살펴보기의 시도 줄")
                .isEmpty();
    }

    private void assertDay7(DayResult day) {
        assertThat(day.loopStatus()).as("7일 시도").isEqualTo(LoopRunStatus.SKIPPED.name());
        assertThat(day.skippedReason()).isEqualTo(LoopSkippedReason.SNOOZED.name());
        assertThat(day.modelCalls()).as("7일 모델 호출").isZero();
    }

    /** 모든 날에 걸치는 경계다. */
    private void assertSafetyAcrossDays(List<DayResult> days) {
        List<ProactiveCheck> scheduled = checksOfAgent().stream()
                .filter(check -> check.trigger() == CheckTrigger.SCHEDULED)
                .toList();
        Map<Long, Long> runsPerSource = loopRuns.findAll().stream()
                .filter(run -> run.userId().equals(owner.id()))
                .collect(Collectors.groupingBy(ProactiveLoopRun::sourceCheckId, Collectors.counting()));
        assertThat(runsPerSource.values()).as("원천 살펴보기마다 시도 줄").allMatch(count -> count == 1L);
        assertThat(runsPerSource.keySet())
                .as("시도 줄이 있는 원천은 모두 매일 깨우기 살펴보기다")
                .isSubsetOf(scheduled.stream().map(ProactiveCheck::id).toList());

        Map<Long, ProactiveCheckProblem> problemById =
                problems
                        .findByCheckIdInOrderByIdAsc(
                                checksOfAgent().stream().map(ProactiveCheck::id).toList())
                        .stream()
                        .collect(Collectors.toMap(ProactiveCheckProblem::id, each -> each));
        for (AutonomyDecision decision : userDecisions()) {
            if (decision.level() != AutonomyLevel.EXECUTE) {
                continue;
            }
            ProactiveCheckProblem problem = problemById.get(decision.candidateId());
            assertThat(problem.sideEffect()).as("EXECUTE 를 받은 후보의 부작용 힌트").isEqualTo("NONE");
            assertThat(truthOf(problem.problemKey()).requiresApproval())
                    .as("승인이 필요한 후보 %s 는 EXECUTE 를 받지 않는다", problem.problemKey())
                    .isFalse();
        }

        for (DayResult day : days) {
            if (day.levels().values().stream().allMatch(AutonomyLevel.IGNORE.name()::equals)) {
                assertThat(day.notificationDelta())
                        .as("%d일은 IGNORE 만 남아 알림이 늘지 않는다", day.day())
                        .isZero();
            }
        }

        Map<Long, DecisionRecord> records = exporter.export(owner, Duration.ofDays(30)).records().stream()
                .filter(each -> each.situation() != null)
                .collect(Collectors.toMap(each -> each.situation().checkId(), each -> each));
        for (DayResult day : days) {
            ProactiveLoopRun run = loopRuns.findBySourceCheckId(day.checkId()).orElseThrow();
            Loop loop = records.get(day.checkId()).situation().loop();
            assertThat(loop).as("%d일 export 의 시도", day.day()).isNotNull();
            assertThat(loop.runId()).isEqualTo(run.id());
            assertThat(loop.status()).isEqualTo(run.status());
            assertThat(loop.skippedReason()).isEqualTo(run.skippedReason());
            assertThat(loop.errorCode()).isEqualTo(run.errorCode());
            assertThat(loop.evaluationId()).isEqualTo(run.evaluationId());
        }
    }

    /** 후보마다 수준이 그 후보의 허용 목록에 든다. 동의 전에는 허용 목록의 EXECUTE 를 SURFACE 로 읽는다. */
    private void assertLevelsAllowed(DayResult day, boolean consented) {
        day.levels().forEach((key, level) -> {
            Truth truth = truthOf(key);
            Set<String> allowed = new HashSet<>(truth.allowed());
            if (!consented && allowed.contains(AutonomyLevel.EXECUTE.name())) {
                allowed.add(AutonomyLevel.SURFACE.name());
            }
            assertThat(allowed).as("%d일 %s 의 수준 %s", day.day(), key, level).contains(level);
        });
    }

    /** 그날 예정 시각 30초 뒤에 깨우고 루프가 끝나기를 기다린 뒤 그날 결과를 모은다. */
    private DayResult runDay(int day, String label, Supplier<String> output, boolean consented)
            throws InterruptedException {
        // 결과 블록의 확인 시각이 깨우는 시각이어야 해서 Hermes 가 답하는 때 만든다.
        stub().willAnswer(command -> answer(output.get()));
        // 보고가 생긴 날은 다음 날 전에 열어 둔다. 열지 않은 보고가 있으면 다음 깨우기가 모델 없이 건너뛴다.
        openUnreadReports();
        long notifications = count("notification");
        long followUps = count("follow_up");
        long approvals = count("connector_action");
        int callsBefore = provider.calls();
        long lastCheckId =
                checksOfAgent().stream().mapToLong(ProactiveCheck::id).max().orElse(0);

        Instant at = wakeAt(day);
        clock.set(at);
        dispatcher.tick(at);
        ProactiveCheck started = awaitNewCheck(lastCheckId);
        backgroundTasks.awaitIdle(IDLE_LIMIT);
        // 살펴보기가 끝났으니 1분 뒤 다시 돌려 발화 줄에 결과를 회복한다.
        Instant later = at.plus(Duration.ofMinutes(1));
        clock.set(later);
        dispatcher.tick(later);
        backgroundTasks.awaitIdle(IDLE_LIMIT);

        ProactiveCheck check = checks.findById(started.id()).orElseThrow();
        ProactiveLoopRun run = loopRuns.findBySourceCheckId(check.id()).orElseThrow();
        List<ProactiveCheckProblem> found = problems.findByCheckIdInOrderByIdAsc(List.of(check.id()));
        Map<Long, String> keys =
                found.stream().collect(Collectors.toMap(ProactiveCheckProblem::id, ProactiveCheckProblem::problemKey));
        List<ValueEvaluation> judged =
                evaluations.findByUserIdAndCheckIdInOrderByIdAsc(owner.id(), List.of(check.id()));
        List<AutonomyDecision> decided = judged.isEmpty()
                ? List.of()
                : decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(
                        owner.id(), judged.stream().map(ValueEvaluation::id).toList());
        Map<String, String> levels = new LinkedHashMap<>();
        decided.forEach(
                each -> levels.put(keys.get(each.candidateId()), each.level().name()));
        int modelCalls = provider.calls() - callsBefore;
        EvalDataset.ProviderProfile profile = DATASET.providers().get(PROVIDER);
        return new DayResult(
                day,
                label,
                consented,
                check.id(),
                run.status().name(),
                run.skippedReason() == null ? null : run.skippedReason().name(),
                run.errorCode(),
                judged.isEmpty() ? null : judged.getFirst().outcome().name(),
                levels,
                found.stream()
                        .filter(each -> each.status() == ProblemStatus.ACCEPTED)
                        .map(ProactiveCheckProblem::problemKey)
                        .toList(),
                found.stream()
                        .filter(each -> each.status() == ProblemStatus.DROPPED)
                        .map(each -> each.problemKey() + ":" + each.dropReason())
                        .toList(),
                modelCalls,
                profile.latencyMs() * modelCalls,
                (profile.inputTokens() + profile.outputTokens()) * modelCalls,
                profile.costMicroUsd() * modelCalls,
                (int) decided.stream()
                        .filter(each -> each.executionStatus() == AutonomyExecutionStatus.STARTED)
                        .count(),
                check.report() != null,
                evaluationCount(),
                count("notification") - notifications,
                count("follow_up") - followUps,
                count("connector_action") - approvals);
    }

    private static Instant wakeAt(int day) {
        return FIRST_WAKE.plus(Duration.ofDays(day - 1L)).plusSeconds(30);
    }

    private void openUnreadReports() {
        for (ProactiveCheck each : checksOfAgent()) {
            if (each.report() != null && each.reportOpenedAt() == null) {
                checkService.openReport(owner, each.id());
            }
        }
    }

    private ProactiveCheck awaitNewCheck(long afterId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (true) {
            List<ProactiveCheck> fresh = checksOfAgent().stream()
                    .filter(each -> each.id() > afterId && each.trigger() == CheckTrigger.SCHEDULED)
                    .toList();
            if (!fresh.isEmpty()) {
                return fresh.getFirst();
            }
            if (System.nanoTime() > deadline) {
                fail("매일 깨우기 살펴보기가 %s 안에 시작되지 않았다", WAIT_LIMIT);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("기다리는 중에 끊겼다");
            }
        }
    }

    private Counts counts() {
        long started = userDecisions().stream()
                .filter(each -> each.executionStatus() == AutonomyExecutionStatus.STARTED)
                .count();
        return new Counts(
                loopRuns.findAll().stream()
                        .filter(run -> run.userId().equals(owner.id()))
                        .count(),
                evaluationCount(),
                userDecisions().size(),
                started);
    }

    private List<AutonomyDecision> userDecisions() {
        List<Long> evaluationIds = evaluations
                .findByUserIdAndCheckIdInOrderByIdAsc(
                        owner.id(),
                        checksOfAgent().stream().map(ProactiveCheck::id).toList())
                .stream()
                .map(ValueEvaluation::id)
                .toList();
        return evaluationIds.isEmpty()
                ? List.of()
                : decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(owner.id(), evaluationIds);
    }

    private long evaluationCount() {
        return count("proactive_value_evaluation");
    }

    private long count(String table) {
        Long count =
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, owner.id());
        return count == null ? 0 : count;
    }

    private List<ProactiveCheck> checksOfAgent() {
        if (agent == null) {
            return List.of();
        }
        return checks.findAll().stream()
                .filter(check -> check.agentId().equals(agent.id()))
                .sorted(Comparator.comparing(ProactiveCheck::id))
                .toList();
    }

    private static Truth truthOf(String problemKey) {
        return DATASET.scenarios().stream()
                .map(scenario -> scenario.truth().get(problemKey))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 날마다 결과를 합쳐 지표를 센다. 중요한 날은 1, 5, 6일이고 침묵이어야 하는 날은 2, 3, 4, 7일이다.
     */
    private Summary summarize(List<DayResult> days) {
        List<Integer> importantDays = List.of(1, 5, 6);
        List<Integer> silentDays = List.of(2, 3, 4, 7);
        long hits = importantDays.stream()
                .filter(day -> {
                    List<String> levels = importantLevels(days.get(day - 1));
                    return !levels.isEmpty() && !levels.contains(AutonomyLevel.IGNORE.name());
                })
                .count();
        long duplicatesPassed =
                days.get(1).acceptedKeys().stream().filter(DEADLINE::equals).count();
        long silentKept = silentDays.stream()
                .map(day -> days.get(day - 1))
                .filter(day -> day.levels().values().stream().allMatch(AutonomyLevel.IGNORE.name()::equals)
                        && day.notificationDelta() == 0
                        && day.followUpDelta() == 0
                        && day.approvalDelta() == 0)
                .count();
        long failures = days.stream()
                .filter(day -> LoopRunStatus.FAILED.name().equals(day.loopStatus())
                        || DecisionOutcome.FALLBACK.name().equals(day.evaluationOutcome()))
                .count();
        int calls = days.stream().mapToInt(DayResult::modelCalls).sum();
        return new Summary(
                importantDays.size(),
                hits,
                duplicatesPassed,
                silentDays.size(),
                silentKept,
                failures,
                calls,
                days.stream().mapToLong(DayResult::simulatedLatencyMs).sum(),
                days.stream().mapToLong(DayResult::simulatedTokens).sum(),
                days.stream().mapToLong(DayResult::simulatedCostMicroUsd).sum());
    }

    private void writeReport(List<DayResult> days, Summary summary) {
        StringBuilder md = new StringBuilder();
        md.append("# 매일 루프 7일 합성 반복\n\n");
        md.append("합성 provider 결과이며 실제 provider 결과가 아니다.\n");
        md.append("provider 는 `").append(PROVIDER).append("` 이고 비용과 지연은 fixture 에 적은 값에 호출 수를 곱한 흉내 값이다.\n\n");
        md.append("## 날마다\n\n");
        md.append(
                "| 날 | 상황 | 시도 | 까닭 | 평가 | 후보별 수준 | 버린 후보 | 모델 호출 | 흉내 지연(ms) | 흉내 토큰 | 흉내 비용(micro USD) | 자동 실행 | 알림 | 할 일 | 승인 줄 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (DayResult day : days) {
            md.append("| %d | %s | %s | %s | %s | %s | %s | %d | %d | %d | %d | %d | %d | %d | %d |\n"
                    .formatted(
                            day.day(),
                            day.label(),
                            day.loopStatus(),
                            day.skippedReason() == null ? "-" : day.skippedReason(),
                            day.evaluationOutcome() == null ? "-" : day.evaluationOutcome(),
                            day.levels().isEmpty() ? "-" : day.levels().toString(),
                            day.droppedKeys().isEmpty()
                                    ? "-"
                                    : day.droppedKeys().toString(),
                            day.modelCalls(),
                            day.simulatedLatencyMs(),
                            day.simulatedTokens(),
                            day.simulatedCostMicroUsd(),
                            day.autonomousStarted(),
                            day.notificationDelta(),
                            day.followUpDelta(),
                            day.approvalDelta()));
        }
        md.append("\n## 합계\n\n");
        md.append("| 지표 | 값 |\n| --- | --- |\n");
        md.append("| 중요한 문제 적중 | %d / %d |\n".formatted(summary.importantHits(), summary.importantTotal()));
        md.append("| 중복 제안(2일의 되풀이가 통과한 수) | %d |\n".formatted(summary.duplicatesPassed()));
        md.append("| 유용한 침묵 | %d / %d |\n".formatted(summary.silentKept(), summary.silentTotal()));
        md.append("| 실패(FALLBACK, FAILED 시도 수) | %d |\n".formatted(summary.failures()));
        md.append("| 모델 호출 수 | %d |\n".formatted(summary.modelCalls()));
        md.append("| 흉내 지연(ms) | %d |\n".formatted(summary.simulatedLatencyMs()));
        md.append("| 흉내 토큰 | %d |\n".formatted(summary.simulatedTokens()));
        md.append("| 흉내 비용(micro USD) | %d |\n".formatted(summary.simulatedCostMicroUsd()));
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("report.md"), md.toString());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("note", "합성 provider 결과이며 실제 provider 결과가 아니다");
            json.put("provider", PROVIDER);
            json.put("days", days);
            json.put("summary", summary);
            Files.writeString(
                    REPORT_DIR.resolve("report.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.println(md);
    }

    /**
     * fixture 의 한 시나리오 확인에서 고른 문제 키의 후보와 그 근거 발견만 담은 버전 3 결과 블록이다. 확인 시각은 지금이다.
     *
     * @param changes 문제 키마다 달 {@code changeSinceLast}
     */
    private String output(EvalDataset.Check fixture, Set<String> problemKeys, Map<String, String> changes) {
        List<EvalDataset.Problem> kept = fixture.problems().stream()
                .filter(problem -> problemKeys.contains(problem.problemKey()))
                .toList();
        Set<String> topics =
                kept.stream().flatMap(problem -> problem.evidence().stream()).collect(Collectors.toSet());
        ObjectNode root = JSON.createObjectNode().put("version", 3).put("outcome", "FINDINGS");
        ArrayNode findings = root.putArray("findings");
        for (EvalDataset.Finding finding : fixture.findings()) {
            if (!topics.contains(finding.topicKey())) {
                continue;
            }
            ObjectNode node = findings.addObject()
                    .put(
                            "area",
                            finding.topicKey().substring(0, finding.topicKey().indexOf(':')))
                    .put("topicKey", finding.topicKey())
                    .put("title", finding.title())
                    .put(
                            "sourceUrl",
                            "https://example.com/" + finding.topicKey().replace(':', '/'))
                    .put("checkedAt", clock.instant().toString())
                    .put("freshness", "CURRENT")
                    .put("whyItMatters", "합성 fixture 의 발견이다");
            node.putArray("facts").add("합성 사실");
            node.putObject("next").put("type", "QUESTION").put("text", "살펴볼까요");
        }
        ArrayNode candidates = root.putArray("problemCandidates");
        for (EvalDataset.Problem problem : kept) {
            ObjectNode node = candidates
                    .addObject()
                    .put("problemKey", problem.problemKey())
                    .put("problem", problem.problem())
                    .put("relatedGoal", problem.relatedGoal())
                    .put("confidence", problem.confidence())
                    .put("expectedBenefit", problem.expectedBenefit())
                    .put("sideEffect", problem.sideEffect());
            ArrayNode evidence = node.putArray("evidence");
            problem.evidence().forEach(evidence::add);
            node.putObject("proposedAction").put("type", problem.actionType()).put("text", problem.actionText());
            if (problem.risk() != null) {
                node.put("risk", problem.risk());
            }
            String change = changes.getOrDefault(problem.problemKey(), problem.changeSinceLast());
            if (change != null) {
                node.put("changeSinceLast", change);
            }
        }
        ObjectNode report = root.putObject("report");
        report.putArray("changed").add("합성 변화");
        report.putArray("done").add("합성 확인");
        report.putArray("next").add("합성 다음");
        return "블록 밖의 글\n<fos-check-result>\n" + JSON.writeValueAsString(root) + "\n</fos-check-result>";
    }

    private static HermesRunResult answer(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 예약 작업 줄은 모든 시험이 공유하므로 지난 줄이 이번 발화에 섞이지 않게 비운다. */
    private void cleanTasks() {
        jdbc.update("DELETE FROM task_run");
        jdbc.update("DELETE FROM task_trigger");
        jdbc.update("DELETE FROM task");
    }

    /** 같은 끝 사건을 다시 낸 전후로 견주는 수다. */
    private record Counts(long runs, long evaluations, long decisions, long autonomousStarted) {}

    /** 하루의 결과다. 보고서에 그대로 실린다. */
    private record DayResult(
            int day,
            String label,
            boolean consented,
            long checkId,
            String loopStatus,
            String skippedReason,
            String errorCode,
            String evaluationOutcome,
            Map<String, String> levels,
            List<String> acceptedKeys,
            List<String> droppedKeys,
            int modelCalls,
            long simulatedLatencyMs,
            long simulatedTokens,
            long simulatedCostMicroUsd,
            int autonomousStarted,
            boolean reportSurfaced,
            long evaluationTotal,
            long notificationDelta,
            long followUpDelta,
            long approvalDelta) {}

    /** 7일 합계다. 흉내 값은 fixture 가 적은 provider 기록에 호출 수를 곱한 것이다. */
    private record Summary(
            int importantTotal,
            long importantHits,
            long duplicatesPassed,
            int silentTotal,
            long silentKept,
            long failures,
            int modelCalls,
            long simulatedLatencyMs,
            long simulatedTokens,
            long simulatedCostMicroUsd) {}
}
