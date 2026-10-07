package com.bifos.assistant.proactive.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.model.FeedbackLabel;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.application.AutonomyProperties;
import com.bifos.assistant.proactive.application.DecisionFeedbackExporter;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.application.ValueEvaluationService;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.DecisionRecord;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.AutonomyInputs;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.eval.EvalDataset.Scenario;
import com.bifos.assistant.proactive.eval.EvalScoreboard.CandidateOutcome;
import com.bifos.assistant.proactive.eval.EvalScoreboard.HardGates;
import com.bifos.assistant.proactive.eval.EvalScoreboard.RunOutcome;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 먼저 살펴보기 루프 전체를 합성 fixture 로 replay 하는 평가 시험이다(ADR-20261007 proactive-eval).
 *
 * <p>Observe(대역 Hermes 가 낸 결과 블록) → 문제 찾기 → 가치 판단(결정적 provider) → 행동 정책 → Hermes 자동 실행 → 결과 → 판단 피드백
 * export 까지 실제 서비스로 돈다. 같은 fixture 를 provider 마다 다시 돌려 판단과 최종 행동 수준을 따로 센다. 실제 모델과 Hermes 는 부르지
 * 않는다. 경계(approval bypass, permission bypass, 근거가 약한 자동 실행, 자동 실행 결과 노출)가 하나라도 깨지면 실패한다. 자동 실행의 대역 답은 새 발견과 할 일 후보를 담아 노출 검사가 걸릴 수 있게 한다. 품질 지표는
 * 보고서에만 남기고 승자를 정하지 않는다.
 */
@SpringBootTest(
        properties = {
            "hermes.run-timeout=30s",
            "assistant.proactive-check.max-duration=20s",
            "assistant.autonomy.execution-enabled=true"
        })
@ActiveProfiles("test")
@Import(ProactiveEvalGateTest.Providers.class)
class ProactiveEvalGateTest {

    static final EvalDataset DATASET = EvalDataset.load();
    static final List<String> COMPARED = List.of("fixture-a", "fixture-b");
    static final Path REPORT_DIR = Path.of("build", "reports", "proactive-eval");
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String READ_ONLY_INSTRUCTION = "이번 실행은 읽기만 한다";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 시나리오마다 이 시각에서 시작한다. 살펴보기, 판단, 행동 정책이 모두 이 시계를 쓴다. */
    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");

    private static final EvalClock CLOCK = new EvalClock(BASE);

    @TestConfiguration
    static class Providers {
        @Bean
        @Primary
        StubHermesRunsClient evalStubHermesRunsClient() {
            return new StubHermesRunsClient();
        }

        @Bean
        @Primary
        Clock evalClock() {
            return CLOCK;
        }

        @Bean
        ReplayDecisionProvider fixtureA() {
            return new ReplayDecisionProvider("fixture-a", ReplayDecisionProvider.Mode.REPLAY, DATASET);
        }

        @Bean
        ReplayDecisionProvider fixtureB() {
            return new ReplayDecisionProvider("fixture-b", ReplayDecisionProvider.Mode.REPLAY, DATASET);
        }

        @Bean
        ReplayDecisionProvider fixtureUnavailable() {
            return new ReplayDecisionProvider("fixture-unavailable", ReplayDecisionProvider.Mode.UNAVAILABLE, DATASET);
        }

        @Bean
        ReplayDecisionProvider fixtureTimeout() {
            return new ReplayDecisionProvider("fixture-timeout", ReplayDecisionProvider.Mode.TIMEOUT, DATASET);
        }

        @Bean
        ReplayDecisionProvider fixtureError() {
            return new ReplayDecisionProvider("fixture-error", ReplayDecisionProvider.Mode.ERROR, DATASET);
        }

        @Bean
        ReplayDecisionProvider fixtureInvalid() {
            return new ReplayDecisionProvider("fixture-invalid", ReplayDecisionProvider.Mode.INVALID, DATASET);
        }
    }

    @Autowired
    ProactiveCheckService checkService;

    @Autowired
    ValueEvaluationService evaluations;

    @Autowired
    AutonomyPolicyService autonomy;

    @Autowired
    DecisionFeedbackExporter exporter;

    @Autowired
    AutonomyProperties autonomyProperties;

    @Autowired
    List<ReplayDecisionProvider> providers;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TurnCancellation turns;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    HermesToolsetClient toolsets;

    @MockitoBean
    HermesSkillClient skillClient;

    @MockitoBean
    AgentConnectorBindings connectorBindings;

    @MockitoBean
    HermesRunEventStream eventStream;

    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stub().reset();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        when(connectorBindings.connectorServers(any())).thenReturn(Set.of());
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
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
        createdUsers.clear();
    }

    @Test
    @DisplayName("여섯 시나리오와 안전 시나리오를 두 provider 로 replay 해도 승인 우회, 권한 우회, 근거가 약한 자동 실행이 0 이다")
    void replaysScenariosWithEveryProviderAndKeepsHardGates() {
        List<RunOutcome> runs = new ArrayList<>();
        for (Scenario scenario : DATASET.scenarios()) {
            for (String providerId : COMPARED) {
                runs.add(run(scenario, providerId));
            }
        }
        List<RunOutcome> fallbackRuns = new ArrayList<>();
        for (String scenarioId : DATASET.fallback().scenarios()) {
            for (String providerId : DATASET.fallback().providers()) {
                fallbackRuns.add(run(DATASET.scenario(scenarioId), providerId));
            }
        }
        List<RunOutcome> all = new ArrayList<>(runs);
        all.addAll(fallbackRuns);
        HardGates gates = EvalScoreboard.hardGates(all);
        List<String> reported = new ArrayList<>(COMPARED);
        reported.addAll(DATASET.fallback().providers());
        writeReport(EvalScoreboard.markdown(reported, all, gates), runs, gates);

        assertThat(gates.passed()).as("경계 검사 %s", gates).isTrue();
        for (RunOutcome run : runs) {
            Map<String, String> levels = run.candidates().stream()
                    .filter(each -> !each.level().equals(EvalScoreboard.DROPPED))
                    .collect(Collectors.toMap(CandidateOutcome::problemKey, CandidateOutcome::level));
            assertThat(levels)
                    .as("%s / %s 의 행동 수준이 기록과 같다", run.scenarioId(), run.providerId())
                    .isEqualTo(DATASET.scenario(run.scenarioId()).snapshotOf(run.providerId()));
            assertThat(run.ledgerLinked())
                    .as("%s / %s 가 판단 피드백 export 의 결정 하나로 이어진다", run.scenarioId(), run.providerId())
                    .isTrue();
        }
        Map<String, List<List<String>>> problemStages = runs.stream()
                .collect(Collectors.groupingBy(
                        RunOutcome::scenarioId,
                        LinkedHashMap::new,
                        Collectors.mapping(RunOutcome::problemStage, Collectors.toList())));
        problemStages.forEach((scenario, stages) -> assertThat(stages)
                .as("%s 의 문제 찾기 결과는 provider 와 무관하다", scenario)
                .allSatisfy(stage -> assertThat(stage).isEqualTo(stages.getFirst())));
        for (RunOutcome run : fallbackRuns) {
            assertThat(run.evaluation())
                    .as("%s / %s 는 fallback", run.scenarioId(), run.providerId())
                    .isEqualTo(DecisionOutcome.FALLBACK);
            assertThat(run.candidates())
                    .as("fallback 은 순서를 대신 만들지 않고 아무것도 올리지 않는다")
                    .allSatisfy(each -> assertThat(each.level()).isEqualTo(AutonomyLevel.IGNORE.name()));
            assertThat(run.autonomousRuns()).isZero();
        }
    }

    /** 사용자와 에이전트를 새로 만들어 시나리오 하나를 끝까지 돈다. */
    private RunOutcome run(Scenario scenario, String providerId) {
        stub().reset();
        CLOCK.set(BASE);
        CurrentUser user = newUser();
        Agent agent = newAgent(user, scenario.agentWritesAllowed());
        autonomy.changeReadOnlyExecution(user, true);
        for (EvalDataset.Check earlier : scenario.historyOrEmpty()) {
            runCheck(user, agent, earlier);
            CLOCK.advance(Duration.ofHours(scenario.historyGapHours()));
        }
        ProactiveCheck check = runCheck(user, agent, scenario.check());
        List<ProactiveCheckProblem> found = problems.findByCheckIdInOrderByIdAsc(List.of(check.id()));
        CLOCK.advance(Duration.ofHours(scenario.decisionDelayHours()));

        ReplayDecisionProvider provider = provider(providerId);
        int callsBefore = provider.calls();
        int submittedBefore = stub().received().size();
        long exposedBefore = exposed(user, check.conversationId());
        ValueEvaluation evaluation = evaluations.evaluate(user, check.id(), providerId);
        int modelCalls = provider.calls() - callsBefore;

        // 자동 실행이 새 발견과 할 일 후보를 내도 사용자에게 바로 보이면 안 된다. 노출 검사가 실제로 걸릴 수 있는 답을 준다.
        String autonomousOutput = block(autonomousBlock(scenario.check()));
        stub().willAnswer(command -> answer(autonomousOutput));
        List<AutonomyDecision> decisions = autonomy.decide(user, evaluation.id());
        awaitIdle(check.conversationId());
        List<HermesRunCommand> delegated = List.copyOf(
                stub().received().subList(submittedBefore, stub().received().size()));
        int autonomousMessages = (int) (exposed(user, check.conversationId()) - exposedBefore);

        Map<Long, AutonomyDecision> byCandidate =
                decisions.stream().collect(Collectors.toMap(AutonomyDecision::candidateId, each -> each));
        List<CandidateOutcome> outcomes = new ArrayList<>();
        for (ProactiveCheckProblem problem : found) {
            AutonomyDecision decision = byCandidate.get(problem.id());
            outcomes.add(new CandidateOutcome(
                    problem.problemKey(),
                    decision == null ? EvalScoreboard.DROPPED : decision.level().name(),
                    decision == null ? List.of() : decision.reasons(),
                    problem.sideEffect(),
                    decision != null && decision.executionStatus() == AutonomyExecutionStatus.STARTED,
                    decision != null && weakBasis(decision.inputs(), decision.reasons()),
                    scenario.truth().get(problem.problemKey())));
        }
        Map<Long, String> keys =
                found.stream().collect(Collectors.toMap(ProactiveCheckProblem::id, ProactiveCheckProblem::problemKey));
        List<String> ranked = evaluation.evidence().result().orderedCandidateIds().stream()
                .map(keys::get)
                .toList();
        EvalDataset.ProviderProfile profile = DATASET.providers().get(providerId);
        boolean called = modelCalls > 0;
        return new RunOutcome(
                scenario.id(),
                providerId,
                evaluation.outcome(),
                List.copyOf(outcomes),
                ranked,
                scenario.truthRank(),
                modelCalls,
                stub().received().size(),
                delegated.size(),
                (int) delegated.stream()
                        .filter(command -> command.instructions() == null
                                || !command.instructions().contains(READ_ONLY_INSTRUCTION))
                        .count(),
                autonomousMessages,
                scenario.agentWritesAllowed(),
                ledgerLinked(user, check, found, evaluation, decisions),
                found.stream()
                        .map(each -> each.problemKey() + ":" + each.status() + ":" + each.dropReason())
                        .toList(),
                called && profile != null ? profile.latencyMs() * modelCalls : 0,
                called && profile != null ? (profile.inputTokens() + profile.outputTokens()) * modelCalls : 0,
                called && profile != null ? profile.costMicroUsd() * modelCalls : 0);
    }

    /**
     * 판단 피드백 export 의 결정 하나({@code check:<번호>})에 상황, 후보, 판단, 정책이 모두 있고, 자동 실행했으면 그 결과가 보이지 않은 실행 결과로
     * 같은 결정에 묶였는지 본다.
     */
    private boolean ledgerLinked(
            CurrentUser user,
            ProactiveCheck check,
            List<ProactiveCheckProblem> found,
            ValueEvaluation evaluation,
            List<AutonomyDecision> decisions) {
        DecisionFeedbackExport export = exporter.export(user, Duration.ofDays(30));
        DecisionRecord record = export.records().stream()
                .filter(each -> each.decisionKey().equals("check:" + check.id()))
                .findFirst()
                .orElse(null);
        if (record == null || record.situation() == null) {
            return false;
        }
        boolean candidates = record.candidates().size() == found.size();
        boolean judged =
                record.judgments().stream().anyMatch(each -> each.evaluationId().equals(evaluation.id()));
        boolean policies = record.policies().stream()
                .map(DecisionFeedbackExport.Policy::decisionId)
                .collect(Collectors.toSet())
                .equals(decisions.stream().map(AutonomyDecision::id).collect(Collectors.toSet()));
        boolean results = decisions.stream()
                .filter(each -> each.executionStatus() == AutonomyExecutionStatus.STARTED)
                .allMatch(each -> record.subjects().stream()
                        .anyMatch(subject -> subject.subjectKey().equals("proactive_check:" + each.executionCheckId())
                                && subject.outcome() == FeedbackEventType.EXECUTION_SUCCEEDED
                                && subject.label() == FeedbackLabel.NOT_SURFACED));
        return candidates && judged && policies && results;
    }

    /**
     * 근거나 판단이 실행 근거로 약하다. 정책의 판정을 그대로 쓰지 않고 입력에서 따로 판정한다. 이런 후보의 자동 실행은 실패시키는 경계다.
     *
     * <p>근거나 평가가 오래됐거나, 후보, 종합, 여섯 축의 확신 가운데 {@code MEDIUM}, {@code HIGH} 가 아닌 것이 있거나, 평가가 근거 부족이거나
     * replay 다.
     */
    private boolean weakBasis(AutonomyInputs inputs, List<AutonomyReason> reasons) {
        boolean staleEvidence =
                olderThan(inputs.evidenceCheckedAt(), inputs.decidedAt(), autonomyProperties.maxEvidenceAge());
        boolean staleEvaluation =
                olderThan(inputs.evaluatedAt(), inputs.decidedAt(), autonomyProperties.maxEvaluationAge())
                        || olderThan(inputs.asOf(), inputs.decidedAt(), autonomyProperties.maxEvaluationAge());
        boolean lowCandidate =
                !"MEDIUM".equals(inputs.candidateConfidence()) && !"HIGH".equals(inputs.candidateConfidence());
        boolean lowJudgement = !confident(inputs.judgementConfidence())
                || Arrays.stream(DecisionAxis.values())
                        .anyMatch(axis -> !confident(inputs.axisConfidences().get(axis)));
        boolean insufficient = inputs.evaluationOutcome() != DecisionOutcome.EVALUATED || inputs.replay();
        return staleEvidence || staleEvaluation || lowCandidate || lowJudgement || insufficient;
    }

    private static boolean confident(DecisionConfidence confidence) {
        return confidence == DecisionConfidence.MEDIUM || confidence == DecisionConfidence.HIGH;
    }

    private static boolean olderThan(Instant at, Instant now, Duration maxAge) {
        return at == null || at.isAfter(now) || Duration.between(at, now).compareTo(maxAge) > 0;
    }

    /** 사용자에게 보이는 것의 수다. 점검 대화의 메시지, 그 대화의 보고, 알림, 할 일을 센다. */
    private long exposed(CurrentUser user, Long conversationId) {
        Long count = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?)"
                        + " + (SELECT COUNT(*) FROM proactive_check WHERE conversation_id = ? AND report_json IS NOT NULL)"
                        + " + (SELECT COUNT(*) FROM notification WHERE user_id = ?)"
                        + " + (SELECT COUNT(*) FROM follow_up WHERE user_id = ?)",
                Long.class,
                conversationId,
                conversationId,
                user.id(),
                user.id());
        return count == null ? 0 : count;
    }

    private ProactiveCheck runCheck(CurrentUser user, Agent agent, EvalDataset.Check fixture) {
        String output = block(resultBlock(fixture));
        stub().willAnswer(command -> answer(output));
        UUID publicId = checkService.start(user, agent.code(), CheckTrigger.MANUAL);
        Conversation conversation = conversations
                .findByPublicIdAndUserIdAndDeletedAtIsNull(publicId, user.id())
                .orElseThrow();
        awaitIdle(conversation.id());
        ProactiveCheck check = checks.findAll().stream()
                .filter(each -> each.conversationId().equals(conversation.id()))
                .filter(each -> each.trigger() == CheckTrigger.MANUAL)
                .max(Comparator.comparing(ProactiveCheck::id))
                .orElseThrow();
        assertThat(check.status()).as("fixture 살펴보기가 끝났다").isEqualTo(CheckStatus.SUCCEEDED);
        return check;
    }

    /** fixture 의 발견과 문제 후보로 버전 3 결과 블록을 만든다. 확인 시각은 지금에서 거꾸로 센다. */
    private static ObjectNode resultBlock(EvalDataset.Check fixture) {
        Instant now = CLOCK.instant();
        ObjectNode root = JSON.createObjectNode().put("version", 3).put("outcome", "FINDINGS");
        ArrayNode findings = root.putArray("findings");
        for (EvalDataset.Finding finding : fixture.findings()) {
            ObjectNode node = findings.addObject()
                    .put(
                            "area",
                            finding.topicKey().substring(0, finding.topicKey().indexOf(':')))
                    .put("topicKey", finding.topicKey())
                    .put("title", finding.title())
                    .put(
                            "sourceUrl",
                            "https://example.com/" + finding.topicKey().replace(':', '/'))
                    .put("checkedAt", now.toString())
                    .put("freshness", "CURRENT")
                    .put("whyItMatters", "합성 fixture 의 발견이다");
            node.putArray("facts").add("합성 사실");
            node.putObject("next").put("type", "QUESTION").put("text", "살펴볼까요");
        }
        ArrayNode candidates = root.putArray("problemCandidates");
        for (EvalDataset.Problem problem : fixture.problems()) {
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
            if (problem.changeSinceLast() != null) {
                node.put("changeSinceLast", problem.changeSinceLast());
            }
        }
        ObjectNode report = root.putObject("report");
        report.putArray("changed").add("합성 변화");
        report.putArray("done").add("합성 확인");
        report.putArray("next").add("합성 다음");
        return root;
    }

    /** 자동 실행의 답이다. 같은 발견에 새 발견 하나와 할 일 후보를 더한다. */
    private static ObjectNode autonomousBlock(EvalDataset.Check fixture) {
        ObjectNode root = resultBlock(fixture);
        ObjectNode extra = ((ArrayNode) root.get("findings"))
                .addObject()
                .put("area", "autonomy")
                .put("topicKey", "autonomy:extra-finding")
                .put("title", "자동 실행이 찾은 합성 발견")
                .put("sourceUrl", "https://example.com/autonomy/extra")
                .put("checkedAt", CLOCK.instant().toString())
                .put("freshness", "CURRENT")
                .put("whyItMatters", "합성 fixture 의 발견이다");
        extra.putArray("facts").add("합성 사실");
        extra.putObject("next").put("type", "QUESTION").put("text", "살펴볼까요");
        root.putArray("followUpCandidates").add("합성 할 일 후보");
        return root;
    }

    private static String block(ObjectNode json) {
        return "블록 밖의 글\n<fos-check-result>\n" + JSON.writeValueAsString(json) + "\n</fos-check-result>";
    }

    private static HermesRunResult answer(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }

    private CurrentUser newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user =
                users.save(AppUser.of("eval-" + suffix + "@example.com", "사용자A", 1L, UserRole.MEMBER, CLOCK.instant()));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent newAgent(CurrentUser user, boolean writesAllowed) {
        String code = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                CLOCK.instant());
        agent.changeProactiveCheckWritesAllowed(writesAllowed);
        return agents.save(agent);
    }

    private ReplayDecisionProvider provider(String id) {
        return providers.stream()
                .filter(each -> each.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private boolean running(Long conversationId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM proactive_check WHERE conversation_id = ? AND status = 'RUNNING'",
                Long.class,
                conversationId);
        return count != null && count > 0;
    }

    private void awaitIdle(Long conversationId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (turns.markOf(conversationId).running() || running(conversationId)) {
            if (System.nanoTime() > deadline) {
                fail("대화 %d 의 살펴보기가 %s 안에 끝나지 않았다", conversationId, WAIT_LIMIT);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("기다리는 중에 끊겼다");
            }
        }
    }

    /** 보고서를 빌드 디렉터리에 남긴다. CI 로그에서도 보이도록 표준 출력에 함께 낸다. */
    private static void writeReport(String markdown, List<RunOutcome> runs, HardGates gates) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("report.md"), markdown);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("datasetVersion", DATASET.version());
            json.put("hardGates", gates);
            json.put(
                    "scores",
                    COMPARED.stream().map(id -> EvalScoreboard.score(id, runs)).toList());
            json.put("runs", runs);
            Files.writeString(
                    REPORT_DIR.resolve("report.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.println(markdown);
    }
}
