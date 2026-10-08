package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionProblem;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.application.model.CardStatus;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.proactive.application.SurfacedProblems;
import com.bifos.assistant.proactive.application.model.DecisionReaction;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.SurfacedProblemSeed;
import com.bifos.assistant.testsupport.SurfacedProblemSeed.Seeded;
import com.bifos.assistant.testsupport.SurfacedProblemSeed.Spec;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 매일 루프가 보인 판정이 「내 차례」 카드의 먼저 다룰 문제 항목으로 나오는지, 숨기기와 미루기의 판단 피드백 사건이 남는지를 실제 DB 로
 * 본다. 규칙은 {@code docs/backend/attention.md} 의 「후보와 trigger」 의 {@code PROBLEM_SURFACED} 줄이다.
 *
 * <p>소스 전체가 실패하는 경우는 컨텍스트를 나누는 대역이 필요해 단언하지 않고, 한 줄의 글이 비어도 나머지 항목과 카드가 남는지로
 * 본다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
class SurfacedProblemCandidatesTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

    @Autowired
    TestClock clock;

    @Autowired
    AttentionService attention;

    @Autowired
    AttentionControlService controls;

    @Autowired
    SurfacedProblems surfaced;

    @Autowired
    FollowUpService followUps;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    CheckConversations checkConversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    ProactiveLoopRunRepository loopRuns;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private SurfacedProblemSeed seed;
    private CurrentUser dad;
    private Agent career;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        seed = new SurfacedProblemSeed(checkConversations, checks, problems, evaluations, decisions, loopRuns);
        dad = member();
        career = agentOf(dad, "커리어");
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM attention_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM attention_control WHERE user_id = ?", userId);
            SurfacedProblemSeed.deleteRowsOf(jdbc, userId);
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("needs_me 카드에 PROBLEM_SURFACED 항목이 LATER 로 나오고 후보 글을 싣고 건수에 세지 않는다")
    void showsProblemAsLaterItemWithoutCountingNow() {
        Seeded seeded = decided("career:deadline-tomorrow", "관심 포지션의 지원 마감이 내일이다", AutonomyLevel.ASK_APPROVAL);

        AttentionView view = attention.view(dad);

        AttentionItem item = onlyItem(view, CardKey.NEEDS_ME);
        assertThat(item.itemKey()).isEqualTo("autonomy_decision:" + seeded.decisionId());
        assertThat(item.trigger()).isEqualTo(AttentionTrigger.PROBLEM_SURFACED);
        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.title()).isEqualTo("관심 포지션의 지원 마감이 내일이다");
        assertThat(item.problem()).isEqualTo(new AttentionProblem(seeded.decisionId(), "ASK_APPROVAL", "다음 행동을 정한다"));
        assertThat(item.agentName()).isEqualTo("커리어");
        assertThat(item.conversationId())
                .isEqualTo(conversations
                        .findById(seeded.conversationId())
                        .orElseThrow()
                        .publicId());
        assertThat(item.why().confidence().name()).isEqualTo("MODEL_INFERRED");
        assertThat(item.why().sources())
                .extracting(source -> source.source(), source -> source.ref())
                .containsExactly(tuple("AUTONOMY_DECISION", item.itemKey()));
        assertThat(card(view, CardKey.NEEDS_ME).nowCount()).isZero();
        assertThat(view.nowCount()).isZero();
    }

    @Test
    @DisplayName("할 일 제안 하나와 문제 글이 빈 후보의 판정이 함께 있어도 needs_me 는 OK 이고 할 일 제안과 나머지 판정 항목이 남는다")
    void keepsOtherItemsWhenOneRowIsBroken() {
        Conversation conversation =
                conversations.save(Conversation.startedBy(dad.id(), "합성 대화", career.id(), NOW.minusSeconds(3_600)));
        AgentExecution run = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(career.id())
                .profileName(career.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(NOW.minusSeconds(120), NOW.minusSeconds(60))
                .build());
        followUps.propose(dad, conversation.id(), run.id(), "합성 공고 요건 정리", null, false);
        decided("career:blank", "", AutonomyLevel.SURFACE);
        Seeded kept = decided("study:reading-backlog", "읽을 글이 쌓였다", AutonomyLevel.SURFACE);

        AttentionCard needsMe = card(attention.view(dad), CardKey.NEEDS_ME);

        assertThat(needsMe.status()).isEqualTo(CardStatus.OK);
        assertThat(needsMe.items())
                .extracting(AttentionItem::trigger)
                .containsExactlyInAnyOrder(AttentionTrigger.FOLLOW_UP_PROPOSED, AttentionTrigger.PROBLEM_SURFACED);
        assertThat(needsMe.items())
                .filteredOn(item -> item.trigger() == AttentionTrigger.PROBLEM_SURFACED)
                .extracting(AttentionItem::itemKey)
                .containsExactly("autonomy_decision:" + kept.decisionId());
    }

    @Test
    @DisplayName("지금 화면에서 숨기면 ATTENTION_HIDE 사건이 원천 살펴보기와 함께 남고, 미루면 POSTPONED 가 남는다. 둘 다 반응은 아니다")
    void recordsHideAndSnoozeEvents() {
        Seeded hidden = decided("career:deadline-tomorrow", "관심 포지션의 지원 마감이 내일이다", AutonomyLevel.SURFACE);
        Seeded snoozed = decided("study:reading-backlog", "읽을 글이 쌓였다", AutonomyLevel.SURFACE);
        List<AttentionItem> items = card(attention.view(dad), CardKey.NEEDS_ME).items();
        AttentionItem hideItem = itemOf(items, hidden);
        AttentionItem snoozeItem = itemOf(items, snoozed);

        controls.hide(dad, CardKey.NEEDS_ME, hideItem.itemKey(), hideItem.stateKey());
        controls.snooze(dad, CardKey.NEEDS_ME, snoozeItem.itemKey(), NOW.plus(Duration.ofHours(2)));

        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT subject_key, event_type, actor, reason_code, conversation_id, source_check_id,"
                                + " autonomy_decision_id FROM decision_feedback_event"
                                + " WHERE user_id = ? AND event_type IN ('DISMISSED', 'POSTPONED') ORDER BY id",
                        dad.id()))
                .extracting(
                        row -> row.get("SUBJECT_KEY"),
                        row -> row.get("EVENT_TYPE"),
                        row -> row.get("ACTOR"),
                        row -> row.get("REASON_CODE"),
                        row -> row.get("CONVERSATION_ID"),
                        row -> row.get("SOURCE_CHECK_ID"),
                        row -> row.get("AUTONOMY_DECISION_ID"))
                .containsExactly(
                        tuple(
                                "autonomy_decision:" + hidden.decisionId(),
                                "DISMISSED",
                                "USER",
                                "ATTENTION_HIDE",
                                hidden.conversationId(),
                                hidden.checkId(),
                                hidden.decisionId()),
                        tuple(
                                "autonomy_decision:" + snoozed.decisionId(),
                                "POSTPONED",
                                "USER",
                                "ATTENTION_SNOOZE",
                                snoozed.conversationId(),
                                snoozed.checkId(),
                                snoozed.decisionId()));
        assertThat(surfaced.current(dad.id(), List.of(hidden.decisionId(), snoozed.decisionId())))
                .as("숨기기와 미루기는 지금 반응이 아니다")
                .isEmpty();
    }

    @Test
    @DisplayName("반응을 남긴 판정과 점검 대화를 지운 판정은 항목에서 빠진다")
    void dropsReactedAndDeletedConversationItems() {
        Seeded reacted = decided("career:deadline-tomorrow", "관심 포지션의 지원 마감이 내일이다", AutonomyLevel.SURFACE);
        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).hasSize(1);

        surfaced.react(dad, reacted.decisionId(), DecisionReaction.DISMISSED);

        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).isEmpty();

        Seeded deleted = decided("study:reading-backlog", "읽을 글이 쌓였다", AutonomyLevel.SURFACE);
        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).hasSize(1);
        jdbc.update(
                "UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), deleted.conversationId());

        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).isEmpty();
    }

    @Test
    @DisplayName("needs_me 의 먼저 다룰 문제는 늦은 것부터 surface-max-items 개까지만 나온다")
    void limitsItemsToSurfaceMaxItems() {
        Seeded oldest = decidedAt("key:1", NOW.minusSeconds(400));
        Seeded second = decidedAt("key:2", NOW.minusSeconds(300));
        Seeded third = decidedAt("key:3", NOW.minusSeconds(200));
        Seeded latest = decidedAt("key:4", NOW.minusSeconds(100));

        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items())
                .extracting(AttentionItem::itemKey)
                .containsExactly(
                        "autonomy_decision:" + latest.decisionId(),
                        "autonomy_decision:" + third.decisionId(),
                        "autonomy_decision:" + second.decisionId())
                .doesNotContain("autonomy_decision:" + oldest.decisionId());
    }

    @Test
    @DisplayName("가장 최근 판정 셋의 점검 대화를 지우면(soft delete) 그보다 오래된 유효한 판정이 needs_me 에 나온다")
    void fillsFromOlderDecisionsWhenRecentConversationsAreDeleted() {
        // 점검 대화는 에이전트마다 하나라 최근 셋은 서로 다른 에이전트에 둔다
        Seeded valid = decidedAt("key:old", NOW.minusSeconds(400));
        List<Seeded> recent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Agent other = agentOf(dad, "삭제될 에이전트 " + i);
            recent.add(seed.decided(
                    dad,
                    other,
                    new Spec(
                            "key:recent-" + i,
                            "최근 문제 " + i,
                            "다음 행동을 정한다",
                            AutonomyLevel.SURFACE,
                            NOW.minusSeconds(300 - i))));
        }
        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items()).hasSize(3);
        for (Seeded seeded : recent) {
            jdbc.update(
                    "UPDATE conversation SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(NOW),
                    seeded.conversationId());
        }

        assertThat(card(attention.view(dad), CardKey.NEEDS_ME).items())
                .extracting(AttentionItem::itemKey)
                .containsExactly("autonomy_decision:" + valid.decisionId());
    }

    private Seeded decidedAt(String problemKey, Instant at) {
        return seed.decided(
                dad, career, new Spec(problemKey, "문제 " + problemKey, "다음 행동을 정한다", AutonomyLevel.SURFACE, at));
    }

    private Seeded decided(String problemKey, String problem, AutonomyLevel level) {
        return seed.decided(dad, career, new Spec(problemKey, problem, "다음 행동을 정한다", level, NOW.minusSeconds(60)));
    }

    private static AttentionItem itemOf(List<AttentionItem> items, Seeded seeded) {
        return items.stream()
                .filter(item -> item.itemKey().equals("autonomy_decision:" + seeded.decisionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("판정 " + seeded.decisionId() + " 의 항목이 없다: " + items));
    }

    private CurrentUser member() {
        String email = "surfaced-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, String name) {
        String code = "surfaced-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    private static AttentionCard card(AttentionView view, CardKey key) {
        return view.cards().stream()
                .filter(card -> card.key() == key)
                .findFirst()
                .orElseThrow(() -> new AssertionError("카드 " + key + " 가 응답에 없다: " + view.cards()));
    }

    private static AttentionItem onlyItem(AttentionView view, CardKey key) {
        List<AttentionItem> items = card(view, key).items();
        assertThat(items).as("카드 %s 의 항목", key).hasSize(1);
        return items.getFirst();
    }
}
