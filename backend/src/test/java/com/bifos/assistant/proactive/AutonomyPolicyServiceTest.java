package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.AxisJudgement;
import com.bifos.assistant.proactive.domain.CandidateJudgement;
import com.bifos.assistant.proactive.domain.DecisionCandidate;
import com.bifos.assistant.proactive.domain.DecisionEvidence;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionResult;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
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
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 판정 저장, 실행 키, 시작 경로 연결을 본다. 시작 경로는 대역이며 Hermes 를 부르지 않는다. */
@SpringBootTest(properties = "assistant.autonomy.execution-enabled=true")
@ActiveProfiles("test")
class AutonomyPolicyServiceTest {

    private static final CurrentUser OWNER =
            new CurrentUser(951_001L, "owner@example.com", "사용자A", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER = new CurrentUser(951_002L, "other@example.com", "사용자B", 1L, UserRole.ADMIN);

    @Autowired
    AutonomyPolicyService service;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    AutonomyPreferenceRepository preferences;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentRepository agents;

    @Autowired
    TransactionTemplate transactions;

    @MockitoBean
    ProactiveCheckService checkService;

    private Agent agent;
    private Conversation conversation;
    private ProactiveCheck source;
    private ProactiveCheck started;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        transactions.executeWithoutResult(status -> {
            agent = agents.save(Agent.of(
                    "autonomy-" + UUID.randomUUID(),
                    "커리어",
                    "autonomy-profile-" + UUID.randomUUID(),
                    "http://agent-runtime.test/p/autonomy",
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    OWNER.id(),
                    now));
            conversation = conversations.save(Conversation.startedForCheck(OWNER.id(), "행동 정책", agent.id(), now));
            source = succeeded(CheckTrigger.MANUAL, now);
            started = succeeded(CheckTrigger.AUTONOMY, now);
        });
        service.changeReadOnlyExecution(OWNER, true);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            decisions.deleteAll();
            preferences.deleteAll();
            evaluations.deleteAll();
            problems.deleteAll(problems.findByCheckIdAndStatusOrderByIdAsc(source.id(), ProblemStatus.ACCEPTED));
            checks.deleteAllById(List.of(source.id(), started.id()));
            conversations.deleteById(conversation.id());
            agents.deleteById(agent.id());
        });
    }

    @Test
    @DisplayName("안전한 후보는 읽기 전용 살펴보기를 한 번 시작하고 같은 원천을 다시 판정해도 다시 시작하지 않는다")
    void executesOnceForSource() {
        ValueEvaluation evaluation = evaluation(null, candidate("study:example", "NONE"));
        doAnswer(invocation -> {
                    invocation.<Consumer<ProactiveCheck>>getArgument(2).accept(started);
                    return conversation.publicId();
                })
                .when(checkService)
                .startAutonomous(eq(OWNER), eq(agent.code()), any());

        AutonomyDecision first = service.decide(OWNER, evaluation.id()).getFirst();
        AutonomyDecision second = service.decide(OWNER, evaluation.id()).getFirst();

        assertThat(first.level()).isEqualTo(AutonomyLevel.EXECUTE);
        assertThat(first.reasons()).containsExactly(AutonomyReason.READ_ONLY_SAFE);
        assertThat(first.executionStatus()).isEqualTo(AutonomyExecutionStatus.STARTED);
        assertThat(decisions.findById(first.id()).orElseThrow().executionCheckId())
                .isEqualTo(started.id());
        assertThat(second.level()).isEqualTo(AutonomyLevel.IGNORE);
        assertThat(second.reasons()).containsExactly(AutonomyReason.ALREADY_EXECUTED);
        assertThat(second.executionKey()).isNull();
        verify(checkService, times(1)).startAutonomous(any(), any(), any());
    }

    @Test
    @DisplayName("가치가 높은 외부 쓰기 후보는 ASK_APPROVAL 이고 시작 경로를 부르지 않는다")
    void externalWriteNeverStarts() {
        ValueEvaluation evaluation = evaluation(null, candidate("apply:example", "EXTERNAL"));

        AutonomyDecision decision = service.decide(OWNER, evaluation.id()).getFirst();

        assertThat(decision.level()).isEqualTo(AutonomyLevel.ASK_APPROVAL);
        assertThat(decision.reasons()).contains(AutonomyReason.EXTERNAL_WRITE_REQUIRES_APPROVAL);
        assertThat(decision.executionKey()).isNull();
        assertThat(decision.inputs().sideEffect()).isEqualTo("EXTERNAL");
        verifyNoInteractions(checkService);
    }

    @Test
    @DisplayName("동의를 끄면 USER_AUTONOMY_DISABLED 로 SURFACE 이고 시작하지 않는다")
    void withdrawnConsentSurfaces() {
        service.changeReadOnlyExecution(OWNER, false);
        ValueEvaluation evaluation = evaluation(null, candidate("study:example", "NONE"));

        AutonomyDecision decision = service.decide(OWNER, evaluation.id()).getFirst();

        assertThat(decision.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(decision.reasons()).containsExactly(AutonomyReason.USER_AUTONOMY_DISABLED);
        assertThat(service.readOnlyExecutionConsented(OWNER)).isFalse();
        verifyNoInteractions(checkService);
    }

    @Test
    @DisplayName("replay 평가는 다른 조건이 맞아도 시작하지 않는다")
    void replayNeverStarts() {
        ValueEvaluation original = evaluation(null, candidate("study:example", "NONE"));
        ValueEvaluation replay = evaluation(original.id(), candidate("study:example", "NONE"));

        AutonomyDecision decision = service.decide(OWNER, replay.id()).getFirst();

        assertThat(decision.level()).isEqualTo(AutonomyLevel.SURFACE);
        assertThat(decision.reasons()).contains(AutonomyReason.REPLAY_INPUT);
        verifyNoInteractions(checkService);
    }

    @Test
    @DisplayName("시작이 거절되면 FAILED 로 남기고 다시 판정해도 다시 시작하지 않는다")
    void failedStartIsNotRetried() {
        ValueEvaluation evaluation = evaluation(null, candidate("study:example", "NONE"));
        doThrow(new ApiException(ErrorCode.USER_BUSY, "busy"))
                .when(checkService)
                .startAutonomous(any(), any(), any());

        AutonomyDecision failed = service.decide(OWNER, evaluation.id()).getFirst();
        AutonomyDecision again = service.decide(OWNER, evaluation.id()).getFirst();

        assertThat(failed.executionStatus()).isEqualTo(AutonomyExecutionStatus.FAILED);
        assertThat(failed.executionError()).isEqualTo("USER_BUSY");
        assertThat(again.reasons()).containsExactly(AutonomyReason.ALREADY_EXECUTED);
        verify(checkService, times(1)).startAutonomous(any(), any(), any());
    }

    @Test
    @DisplayName("다른 사용자의 평가는 관리자에게도 없는 평가다")
    void otherUserCannotDecide() {
        ValueEvaluation evaluation = evaluation(null, candidate("study:example", "NONE"));

        assertThatThrownBy(() -> service.decide(OTHER, evaluation.id()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALUE_EVALUATION_NOT_FOUND));
        verifyNoInteractions(checkService);
    }

    @Test
    @DisplayName("평가 뒤 후보 줄이 지워졌으면 IGNORE 다")
    void missingCandidateIgnores() {
        ValueEvaluation evaluation = evaluation(null, candidate("study:example", "NONE"));
        transactions.executeWithoutResult(status ->
                problems.deleteAll(problems.findByCheckIdAndStatusOrderByIdAsc(source.id(), ProblemStatus.ACCEPTED)));

        AutonomyDecision decision = service.decide(OWNER, evaluation.id()).getFirst();

        assertThat(decision.level()).isEqualTo(AutonomyLevel.IGNORE);
        assertThat(decision.reasons()).contains(AutonomyReason.CANDIDATE_NOT_CURRENT);
        verifyNoInteractions(checkService);
    }

    private ProactiveCheck succeeded(CheckTrigger trigger, Instant now) {
        ProactiveCheck check = ProactiveCheck.started(OWNER.id(), agent.id(), conversation.id(), trigger, false, now);
        check.succeed(CheckOutcome.FINDINGS, 1, 0, null, 0, 0, 0, 0, 0, now);
        return checks.save(check);
    }

    private ProactiveCheckProblem candidate(String key, String sideEffect) {
        Instant now = Instant.now();
        return transactions.execute(status -> problems.save(ProactiveCheckProblem.of(
                source.id(),
                conversation.id(),
                ProblemStatus.ACCEPTED,
                null,
                key,
                "합성 문제",
                "합성 목표",
                "ACTION",
                "합성 행동",
                "HIGH",
                "합성 효과",
                sideEffect,
                null,
                null,
                List.of(new ProblemEvidence(key, "https://docs.example.com/" + key, now.minusSeconds(60))),
                now.minusSeconds(60),
                now)));
    }

    /** 모든 축이 실행에 맞는 판단을 가진 평가를 저장한다. */
    private ValueEvaluation evaluation(Long replayOfId, ProactiveCheckProblem problem) {
        Instant now = Instant.now();
        DecisionCandidate snapshot = DecisionCandidate.from(problem);
        List<AxisJudgement> axes = Arrays.stream(DecisionAxis.values())
                .map(axis -> new AxisJudgement(
                        axis,
                        axis == DecisionAxis.COST || axis == DecisionAxis.RISK ? DecisionLevel.LOW : DecisionLevel.HIGH,
                        DecisionConfidence.HIGH,
                        "합성 판단",
                        List.of(problem.problemKey())))
                .toList();
        DecisionEvidence evidence = new DecisionEvidence(
                new DecisionState(1, now, List.of(snapshot)),
                List.of(),
                new DecisionProviderInfo("fixture", "1", null, null, null, null, null),
                new DecisionResult(
                        DecisionOutcome.EVALUATED,
                        List.of(new CandidateJudgement(snapshot.candidateId(), axes, DecisionConfidence.HIGH, "합성")),
                        List.of(snapshot.candidateId()),
                        "합성 비교",
                        null));
        return transactions.execute(
                status -> evaluations.save(ValueEvaluation.of(source.id(), OWNER.id(), replayOfId, evidence, now)));
    }
}
