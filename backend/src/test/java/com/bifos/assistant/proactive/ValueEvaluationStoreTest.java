package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.application.DecisionProvider;
import com.bifos.assistant.proactive.application.ValueEvaluationRecovery;
import com.bifos.assistant.proactive.application.ValueEvaluationService;
import com.bifos.assistant.proactive.application.ValueEvaluationStore;
import com.bifos.assistant.proactive.application.ValueEvaluator;
import com.bifos.assistant.proactive.application.model.DecisionRequest;
import com.bifos.assistant.proactive.application.model.DecisionResponse;
import com.bifos.assistant.proactive.domain.DecisionProviderInfo;
import com.bifos.assistant.proactive.domain.DecisionQuestion;
import com.bifos.assistant.proactive.domain.DecisionState;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionFailure;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class ValueEvaluationStoreTest {

    private static final CurrentUser OWNER =
            new CurrentUser(950_001L, "owner@example.com", "사용자A", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER = new CurrentUser(950_002L, "other@example.com", "사용자B", 1L, UserRole.ADMIN);

    @Autowired
    ValueEvaluationStore store;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ValueEvaluationRecovery recovery;

    @Autowired
    JdbcTemplate jdbc;

    private Conversation conversation;
    private ProactiveCheck check;
    private ValueEvaluationService service;

    @BeforeEach
    void setUp() {
        transactions.executeWithoutResult(status -> {
            conversation = conversations.save(
                    Conversation.startedForCheck(OWNER.id(), "가치 평가", 950_101L, DecisionFixtures.NOW));
            check = ProactiveCheck.started(
                    OWNER.id(), 950_101L, conversation.id(), CheckTrigger.MANUAL, false, DecisionFixtures.NOW);
            check.succeed(CheckOutcome.FINDINGS, 2, 0, null, 0, 0, 0, 0, 0, DecisionFixtures.NOW);
            check = checks.save(check);
            for (var candidate : DecisionFixtures.state().candidates()) {
                problems.save(ProactiveCheckProblem.of(
                        check.id(),
                        conversation.id(),
                        ProblemStatus.ACCEPTED,
                        null,
                        candidate.problemKey(),
                        candidate.problem(),
                        candidate.relatedGoal(),
                        candidate.actionType(),
                        candidate.actionText(),
                        candidate.confidence(),
                        candidate.expectedBenefit(),
                        candidate.sideEffect(),
                        candidate.risk(),
                        null,
                        candidate.evidence(),
                        candidate.evidenceCheckedAt(),
                        DecisionFixtures.NOW));
            }
            problems.save(ProactiveCheckProblem.of(
                    check.id(),
                    conversation.id(),
                    ProblemStatus.DROPPED,
                    null,
                    "dropped",
                    "버린 후보",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    DecisionFixtures.NOW));
        });
        service = new ValueEvaluationService(
                List.of(provider("fixture-a"), provider("fixture-b")), new ValueEvaluator(), store);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            evaluations.deleteAll();
            problems.deleteAll(problems.findByCheckIdAndStatusOrderByIdAsc(check.id(), ProblemStatus.ACCEPTED));
            problems.deleteAll(problems.findByCheckIdAndStatusOrderByIdAsc(check.id(), ProblemStatus.DROPPED));
            checks.deleteById(check.id());
            conversations.deleteById(conversation.id());
        });
    }

    @Test
    @DisplayName("같은 후보를 다른 provider 로 두 번 replay 하면 기준 시각과 질문과 설명이 왕복한다")
    void replaysFrozenStateAcrossProvidersTwice() {
        ValueEvaluation first = service.evaluate(OWNER, check.id(), "fixture-a");
        transactions.executeWithoutResult(status -> {
            var candidate = DecisionFixtures.state().candidates().getFirst();
            problems.save(ProactiveCheckProblem.of(
                    check.id(),
                    conversation.id(),
                    ProblemStatus.ACCEPTED,
                    null,
                    "later",
                    "나중에 생긴 후보",
                    "다른 목표",
                    "QUESTION",
                    "나중에 물을까요",
                    "HIGH",
                    "효과",
                    "NONE",
                    null,
                    null,
                    candidate.evidence(),
                    candidate.evidenceCheckedAt(),
                    DecisionFixtures.NOW));
        });
        ValueEvaluation second = service.replay(OWNER, first.id(), "fixture-b");
        ValueEvaluation third = service.replay(OWNER, first.id(), "fixture-a");
        assertThat(second.evidence().state()).isEqualTo(first.evidence().state());
        assertThat(third.evidence().state()).isEqualTo(first.evidence().state());
        assertThat(second.evidence().questions()).isEqualTo(first.evidence().questions());
        assertThat(second.evidence().provider().adapter()).isEqualTo("fixture-b");
        assertThat(second.replayOfId()).isEqualTo(first.id());
        assertThat(service.read(OWNER, third.id()).evidence()).isEqualTo(third.evidence());
        assertThat(first.evidence().state().candidates()).hasSize(2);
        assertThat(third.evidence().result().orderedCandidateIds()).hasSize(2);
        assertThat(third.evidence().result().explanation()).contains("마감 이틀", "실행 부담");
    }

    @Test
    @DisplayName("관리자도 남의 평가를 읽거나 replay 할 수 없고 지운 대화의 평가도 감춘다")
    void hidesOtherUsersAndDeletedConversation() {
        ValueEvaluation first = service.evaluate(OWNER, check.id(), "fixture-a");
        assertHidden(() -> service.read(OTHER, first.id()));
        assertHidden(() -> service.replay(OTHER, first.id(), "fixture-b"));
        assertHidden(() -> service.evaluate(OTHER, check.id(), "fixture-a"));
        transactions.executeWithoutResult(
                status -> conversations.deleteIfActive(conversation.id(), OWNER.id(), DecisionFixtures.NOW));
        assertHidden(() -> service.read(OWNER, first.id()));
        assertHidden(() -> service.replay(OWNER, first.id(), "fixture-b"));
    }

    @Test
    @DisplayName("저장 뒤 중단된 시도는 재시작 정리가 INTERRUPTED 로 닫는다")
    void closesInterruptedAttemptWithoutCallingProvider() {
        ValueEvaluation running = store.begin(OWNER.id(), check.id(), "fixture-a");
        recovery.recover();
        ValueEvaluation ended = store.read(OWNER.id(), running.id());
        assertThat(ended.outcome()).isEqualTo(DecisionOutcome.FALLBACK);
        assertThat(ended.evidence().result().failure()).isEqualTo(DecisionFailure.INTERRUPTED);
        assertThat(ended.evidence().state()).isEqualTo(running.evidence().state());
    }

    @Test
    @DisplayName("판단 중 대화를 지워도 완료 기록은 닫고 응답과 조회는 감춘다")
    void closesAttemptEvenIfConversationIsDeletedDuringProviderCall() {
        DecisionProvider deleting = provider(
                "deleting",
                () -> transactions.executeWithoutResult(
                        status -> conversations.deleteIfActive(conversation.id(), OWNER.id(), DecisionFixtures.NOW)));
        service = new ValueEvaluationService(List.of(deleting), new ValueEvaluator(), store);
        assertHidden(() -> service.evaluate(OWNER, check.id(), "deleting"));
        ValueEvaluation ended = evaluations.findAll().getFirst();
        assertThat(ended.outcome()).isEqualTo(DecisionOutcome.EVALUATED);
        assertThat(ended.evidence().result().orderedCandidateIds()).hasSize(2);
        assertHidden(() -> service.read(OWNER, ended.id()));
        assertHidden(() -> service.replay(OWNER, ended.id(), "deleting"));
        assertHidden(() -> store.finish(OTHER.id(), ended.id(), ended.evidence()));
    }

    @Test
    @DisplayName("잘못된 평가 JSON 한 줄은 다른 평가의 별도 복구 트랜잭션과 기동을 막지 않는다")
    void recoversValidRowsBesideInvalidEvidence() {
        ValueEvaluation bad = store.begin(OWNER.id(), check.id(), "fixture-a");
        ValueEvaluation good = store.begin(OWNER.id(), check.id(), "fixture-a");
        jdbc.update("UPDATE proactive_value_evaluation SET evidence_json = ? WHERE id = ?", "{}", bad.id());
        try {
            recovery.start();
            assertThat(recovery.isRunning()).isTrue();
            assertThat(store.read(OWNER.id(), good.id()).evidence().result().failure())
                    .isEqualTo(DecisionFailure.INTERRUPTED);
            assertThat(jdbc.queryForObject(
                            "SELECT outcome FROM proactive_value_evaluation WHERE id = ?", String.class, bad.id()))
                    .isEqualTo("RUNNING");
        } finally {
            jdbc.update("DELETE FROM proactive_value_evaluation WHERE id = ?", bad.id());
        }
    }

    private DecisionProvider provider(String id) {
        return provider(id, () -> {});
    }

    private DecisionProvider provider(String id, Runnable duringCall) {
        return new DecisionProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DecisionResponse evaluate(
                    DecisionState state, List<DecisionQuestion> questions, DecisionRequest request) {
                duringCall.run();
                return new DecisionResponse(
                        new DecisionProviderInfo(
                                id, "fixture-1", "requested", "test-model", "actual", "test-model", null),
                        DecisionFixtures.ordered(state));
            }
        };
    }

    private void assertHidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALUE_EVALUATION_NOT_FOUND));
    }
}
