package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.proactive.application.SurfacedProblems;
import com.bifos.assistant.proactive.application.model.DecisionReaction;
import com.bifos.assistant.proactive.application.model.SurfacedProblem;
import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.SurfacedProblemSeed;
import com.bifos.assistant.testsupport.SurfacedProblemSeed.Seeded;
import com.bifos.assistant.testsupport.SurfacedProblemSeed.Spec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 매일 루프의 판정을 지금 화면에 보일 문제로 고르는 규칙, 판단 피드백 {@code SURFACED}, 사용자 반응과 그 거절을 본다. 규칙은
 * {@code backend/docs/flow.md} 의 「사용자에게 보이는 것」 이 갖는다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-a"})
class SurfacedProblemsTest extends ProactiveLoopTestSupport {

    private static final String KEY_A = "career:deadline-tomorrow";
    private static final String KEY_B = "study:reading-backlog";

    @Autowired
    SurfacedProblems surfaced;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    DecisionFeedbackRecorder recorder;

    private SurfacedProblemSeed seed;

    @BeforeEach
    void seedUp(@Autowired CheckConversations conversations) {
        seed = new SurfacedProblemSeed(conversations, checks, problems, evaluations, decisions, loopRuns);
    }

    @Test
    @DisplayName("매일 루프가 낸 SURFACE 판정이 openOf 에 나오고 SURFACED 사건이 하나 남는다")
    void showsLoopDecisionAndRecordsSurfaced() throws InterruptedException {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        saveSetting(user, agent, true, null);

        Long checkId = wake(user, agent, candidateOutput());

        List<AutonomyDecision> made = decisions.findAll().stream()
                .filter(decision -> decision.userId().equals(user.id()))
                .toList();
        assertThat(made).as("판정").hasSize(1);
        AutonomyDecision decision = made.getFirst();
        assertThat(decision.level()).as("판정 수준").isEqualTo(AutonomyLevel.SURFACE);
        List<SurfacedProblem> open = surfaced.openOf(user.id(), BASE);
        assertThat(open).as("보일 문제").hasSize(1);
        assertThat(open.getFirst().decisionId()).isEqualTo(decision.id());
        assertThat(open.getFirst().checkId()).isEqualTo(checkId);
        assertThat(events(user, FeedbackEventType.SURFACED))
                .extracting(row -> row.get("SUBJECT_KEY"))
                .containsExactly("autonomy_decision:" + decision.id());
    }

    @Test
    @DisplayName("IGNORE 판정은 나오지 않고 SURFACED 사건도 남기지 않는다")
    void ignoresIgnoreDecision() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded = seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.IGNORE, BASE.minusSeconds(60)));

        surfaced.surfaced(decisions.findById(seeded.decisionId()).orElseThrow());

        assertThat(surfaced.openOf(user.id(), BASE)).isEmpty();
        assertThat(events(user, FeedbackEventType.SURFACED)).isEmpty();
    }

    @Test
    @DisplayName("SURFACED 사건은 점검 대화, 원천 살펴보기, 판정을 채운 SYSTEM 사건이다")
    void recordsSurfacedWithOrigin() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.ASK_APPROVAL, BASE.minusSeconds(60)));

        surfaced.surfaced(decisions.findById(seeded.decisionId()).orElseThrow());

        assertThat(jdbc.queryForList(
                        "SELECT subject_key, actor, conversation_id, source_check_id, autonomy_decision_id"
                                + " FROM decision_feedback_event WHERE user_id = ? AND event_type = 'SURFACED'",
                        user.id()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("SUBJECT_KEY")).isEqualTo("autonomy_decision:" + seeded.decisionId());
                    assertThat(row.get("ACTOR")).isEqualTo("SYSTEM");
                    assertThat(row.get("CONVERSATION_ID")).isEqualTo(seeded.conversationId());
                    assertThat(row.get("SOURCE_CHECK_ID")).isEqualTo(seeded.checkId());
                    assertThat(row.get("AUTONOMY_DECISION_ID")).isEqualTo(seeded.decisionId());
                });
    }

    @Test
    @DisplayName("시도 줄이 없는 평가의 판정은 나오지 않는다")
    void hidesDecisionWithoutRun() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        jdbc.update("DELETE FROM proactive_loop_run WHERE id = ?", seeded.runId());

        assertThat(surfaced.openOf(user.id(), BASE)).isEmpty();
        assertRejected(() -> surfaced.react(user, seeded.decisionId(), DecisionReaction.ACCEPTED));
    }

    @Test
    @DisplayName("DECIDED 가 아닌 시도의 평가에서 나온 판정은 나오지 않는다")
    void hidesDecisionOfFailedRun() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        ProactiveLoopRun run = loopRuns.findById(seeded.runId()).orElseThrow();
        run.failed("INTERNAL_ERROR", seeded.evaluationId(), BASE);
        loopRuns.save(run);

        assertThat(surfaced.openOf(user.id(), BASE)).isEmpty();
    }

    @Test
    @DisplayName("같은 평가를 다시 판정해 생긴 줄은 나오지 않고 반응하면 AUTONOMY_DECISION_NOT_FOUND 다. 처음 판정은 그대로 나온다")
    void excludesRedecidedRow() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        AutonomyDecision again = seed.redecide(user, seeded, AutonomyLevel.ASK_APPROVAL, BASE.minusSeconds(30));

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(seeded.decisionId());
        assertRejected(() -> surfaced.react(user, again.id(), DecisionReaction.ACCEPTED));
        assertThat(events(user, FeedbackEventType.ACCEPTED)).isEmpty();
    }

    @Test
    @DisplayName("같은 문제 키는 새 판정만 나오고, 새 판정에 관심 없음을 남기면 옛 판정도 나오지 않는다")
    void keepsLatestPerProblemKeyBeforeReaction() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        seed.decided(user, agent, spec(KEY_A, "옛 시도의 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(7_200)));
        Seeded newer = seed.decided(user, agent, spec(KEY_A, "새 시도의 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId, SurfacedProblem::problem)
                .containsExactly(tuple(newer.decisionId(), "새 시도의 문제"));

        surfaced.react(user, newer.decisionId(), DecisionReaction.DISMISSED);

        assertThat(surfaced.openOf(user.id(), BASE)).as("옛 판정이 되살아나지 않는다").isEmpty();
    }

    @Test
    @DisplayName("문제 글이 빈 판정이 섞여 있어도 나머지 판정은 나오고 늦은 것부터 정렬한다")
    void skipsOnlyRowsWithoutProblemText() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded empty = seed.decided(user, agent, spec(KEY_A, "  ", AutonomyLevel.SURFACE, BASE.minusSeconds(10)));
        Seeded earlier =
                seed.decided(user, agent, spec(KEY_B, "읽을 글이 쌓였다", AutonomyLevel.SURFACE, BASE.minusSeconds(120)));
        Seeded later = seed.decided(
                user,
                agent,
                spec("event:conference", "행사 신청이 곧 닫힌다", AutonomyLevel.ASK_APPROVAL, BASE.minusSeconds(60)));

        List<SurfacedProblem> open = surfaced.openOf(user.id(), BASE);

        assertThat(open)
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(later.decisionId(), earlier.decisionId());
        assertThat(open.getFirst().level()).isEqualTo(AutonomyLevel.ASK_APPROVAL);
        assertThat(open.getFirst().action()).isEqualTo("다음 행동을 정한다");
        assertThat(empty.decisionId()).isNotNull();
    }

    @Test
    @DisplayName("후보 줄이 지워진 판정은 그 줄만 빠진다")
    void skipsRowsWithoutCandidate() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded gone = seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        Seeded kept =
                seed.decided(user, agent, spec(KEY_B, "읽을 글이 쌓였다", AutonomyLevel.SURFACE, BASE.minusSeconds(120)));
        problems.deleteById(gone.problemId());

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(kept.decisionId());
    }

    @Test
    @DisplayName("받아들임 뒤 그 판정이 빠지고 같은 반응을 다시 보내도 사건이 늘지 않는다")
    void removesDecisionAfterAcceptedAndIgnoresRepeat() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));

        List<Long> before = sideEffectCounts(user);

        surfaced.react(user, seeded.decisionId(), DecisionReaction.ACCEPTED);
        surfaced.react(user, seeded.decisionId(), DecisionReaction.ACCEPTED);

        assertThat(surfaced.openOf(user.id(), BASE)).isEmpty();
        assertThat(sideEffectCounts(user))
                .as("반응 전후의 follow_up, connector_action, agent_execution, notification 줄 수")
                .isEqualTo(before);
        assertThat(jdbc.queryForList(
                        "SELECT actor, conversation_id, source_check_id, autonomy_decision_id FROM decision_feedback_event"
                                + " WHERE user_id = ? AND event_type = 'ACCEPTED'",
                        user.id()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("ACTOR")).isEqualTo("USER");
                    assertThat(row.get("CONVERSATION_ID")).isEqualTo(seeded.conversationId());
                    assertThat(row.get("SOURCE_CHECK_ID")).isEqualTo(seeded.checkId());
                    assertThat(row.get("AUTONOMY_DECISION_ID")).isEqualTo(seeded.decisionId());
                });
        assertThat(surfaced.current(user.id(), List.of(seeded.decisionId())))
                .isEqualTo(Map.of(seeded.decisionId(), DecisionReaction.ACCEPTED));
    }

    @Test
    @DisplayName("openOf 는 surface-max-items 상한을 걸지 않고 늦은 것부터 모두 낸다. 상한은 거르는 쪽이 건다")
    void openOfDoesNotLimitBySurfaceMaxItems() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded oldest =
                seed.decided(user, agent, spec("key:1", "가장 오래된 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(400)));
        Seeded second =
                seed.decided(user, agent, spec("key:2", "둘째 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(300)));
        Seeded third = seed.decided(user, agent, spec("key:3", "셋째 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(200)));
        Seeded latest =
                seed.decided(user, agent, spec("key:4", "가장 늦은 문제", AutonomyLevel.SURFACE, BASE.minusSeconds(100)));

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(latest.decisionId(), third.decisionId(), second.decisionId(), oldest.decisionId());

        surfaced.react(user, oldest.decisionId(), DecisionReaction.ACCEPTED);

        assertThat(events(user, FeedbackEventType.ACCEPTED)).hasSize(1);
    }

    @Test
    @DisplayName("지금 반응은 마지막 사용자 반응이다")
    void currentIsLastUserReaction() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));

        surfaced.react(user, seeded.decisionId(), DecisionReaction.ACCEPTED);
        clock.set(BASE.plusSeconds(5));
        surfaced.react(user, seeded.decisionId(), DecisionReaction.DISMISSED);

        assertThat(surfaced.current(user.id(), List.of(seeded.decisionId())))
                .isEqualTo(Map.of(seeded.decisionId(), DecisionReaction.DISMISSED));
    }

    @Test
    @DisplayName("까닭이 ATTENTION_HIDE 인 DISMISSED 사건만 있는 판정은 그대로 나온다")
    void keepsDecisionWithOnlyHideEvent() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        recorder.record(FeedbackEntry.of(
                        user.id(),
                        FeedbackSubjectType.AUTONOMY_DECISION,
                        seeded.decisionId(),
                        FeedbackEventType.DISMISSED,
                        FeedbackActor.USER,
                        BASE)
                .reason(FeedbackLabeler.ATTENTION_HIDE));

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(seeded.decisionId());
        assertThat(surfaced.current(user.id(), List.of(seeded.decisionId()))).isEmpty();
    }

    @Test
    @DisplayName("남의 판정, IGNORE 판정, 없는 번호에 반응하면 AUTONOMY_DECISION_NOT_FOUND 다")
    void rejectsReactionToUnavailableDecision() {
        CurrentUser user = newUser();
        CurrentUser other = newUser();
        Agent agent = newAgent(user);
        Seeded mine = seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        Seeded ignored =
                seed.decided(user, agent, spec(KEY_B, "읽을 글이 쌓였다", AutonomyLevel.IGNORE, BASE.minusSeconds(60)));

        assertRejected(() -> surfaced.react(other, mine.decisionId(), DecisionReaction.ACCEPTED));
        assertRejected(() -> surfaced.react(user, ignored.decisionId(), DecisionReaction.ACCEPTED));
        assertRejected(() -> surfaced.react(user, Long.MAX_VALUE, DecisionReaction.ACCEPTED));
        assertThat(events(user, FeedbackEventType.ACCEPTED)).isEmpty();
        assertThat(surfaced.openOf(other.id(), BASE)).isEmpty();
    }

    @Test
    @DisplayName("surface-window 보다 오래된 시도의 판정은 나오지 않고, 그 안의 판정은 나온다. 오래된 판정에도 반응은 받는다")
    void respectsSurfaceWindow() {
        CurrentUser user = newUser();
        Agent agent = newAgent(user);
        Seeded old =
                seed.decided(user, agent, spec(KEY_A, "오래된 문제", AutonomyLevel.SURFACE, BASE.minus(Duration.ofDays(8))));
        Seeded recent =
                seed.decided(user, agent, spec(KEY_B, "최근 문제", AutonomyLevel.SURFACE, BASE.minus(Duration.ofDays(6))));

        assertThat(surfaced.openOf(user.id(), BASE))
                .extracting(SurfacedProblem::decisionId)
                .containsExactly(recent.decisionId());

        surfaced.react(user, old.decisionId(), DecisionReaction.DISMISSED);

        assertThat(events(user, FeedbackEventType.DISMISSED)).hasSize(1);
    }

    @Test
    @DisplayName("withOrigin 은 요청자의 판정에만 원천을 채운다")
    void fillsOriginOnlyForOwnDecision() {
        CurrentUser user = newUser();
        CurrentUser other = newUser();
        Agent agent = newAgent(user);
        Seeded seeded =
                seed.decided(user, agent, spec(KEY_A, "마감이 내일이다", AutonomyLevel.SURFACE, BASE.minusSeconds(60)));
        FeedbackEntry base = FeedbackEntry.of(
                user.id(),
                FeedbackSubjectType.AUTONOMY_DECISION,
                seeded.decisionId(),
                FeedbackEventType.POSTPONED,
                FeedbackActor.USER,
                BASE);

        assertThat(surfaced.withOrigin(user.id(), seeded.decisionId(), base)).hasValueSatisfying(entry -> {
            assertThat(entry.conversationId()).isEqualTo(seeded.conversationId());
            assertThat(entry.sourceCheckId()).isEqualTo(seeded.checkId());
            assertThat(entry.autonomyDecisionId()).isEqualTo(seeded.decisionId());
        });
        assertThat(surfaced.withOrigin(other.id(), seeded.decisionId(), base)).isEmpty();
    }

    @Test
    @DisplayName("모르는 반응 글은 VALIDATION_FAILED 다")
    void rejectsUnknownReaction() {
        assertThatThrownBy(() -> DecisionReaction.parse("POSTPONED"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(DecisionReaction.parse("ACCEPTED")).isEqualTo(DecisionReaction.ACCEPTED);
    }

    /** follow_up, connector_action, agent_execution, notification 줄 수다. 반응이 이 줄들을 만들지 않는지 본다. */
    private List<Long> sideEffectCounts(CurrentUser user) {
        return List.of("follow_up", "connector_action", "agent_execution", "notification").stream()
                .map(table -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, user.id()))
                .toList();
    }

    private static Spec spec(String problemKey, String problem, AutonomyLevel level, Instant at) {
        return new Spec(problemKey, problem, "다음 행동을 정한다", level, at);
    }

    private List<Map<String, Object>> events(CurrentUser user, FeedbackEventType type) {
        return jdbc.queryForList(
                "SELECT subject_key FROM decision_feedback_event"
                        + " WHERE user_id = ? AND event_type = ? AND subject_key LIKE 'autonomy_decision:%'",
                user.id(), type.name());
    }

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AUTONOMY_DECISION_NOT_FOUND));
    }
}
