package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.model.FeedbackLabel;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpPatch;
import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.proactive.application.CheckFeedback;
import com.bifos.assistant.proactive.application.DecisionFeedbackExporter;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.DecisionRecord;
import com.bifos.assistant.proactive.application.model.DecisionFeedbackExport.Subject;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 커리어 매일 깨우기의 합성 시나리오로 판단 피드백의 기록 지점과 replay 읽기 모델을 본다. 보고를 보인 살펴보기, 알릴 것이 없던 침묵, 그
 * 살펴보기 트리에서 나온 할 일과 Memory 제안, 사용자의 반응, 대화 삭제가 한 결정으로 이어지는지 확인한다. 실제 개인 정보는 쓰지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionFeedbackFlowTest {

    private static final String FIRST_TITLE = "합성 공고 마감 확인";
    private static final String SECOND_TITLE = "합성 행사 신청";

    @Autowired
    FollowUpService followUps;

    @Autowired
    MemoryService memories;

    @Autowired
    ChatService chat;

    @Autowired
    CheckFeedback checkFeedback;

    @Autowired
    DecisionFeedbackExporter exporter;

    @Autowired
    FeedbackEventRepository events;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private CurrentUser other;
    private Agent career;
    private Conversation checkConversation;
    private AgentExecution root;
    private ProactiveCheck reported;
    private ProactiveCheck silent;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        owner = member(now);
        other = member(now);
        String code = "feedback-" + UUID.randomUUID().toString().substring(0, 8);
        career = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                now));
        checkConversation =
                conversations.save(Conversation.startedForCheck(owner.id(), "커리어 살펴보기", career.id(), now));
        root = executions.save(execution(null, now));
        reported = ProactiveCheck.started(
                owner.id(), career.id(), checkConversation.id(), CheckTrigger.SCHEDULED, false, now.minusSeconds(120));
        reported.attachRoot(root.id(), "session-fixture");
        reported.succeed(
                CheckOutcome.FINDINGS,
                1,
                0,
                new CheckReport(List.of("합성 변화"), List.of(), List.of(), List.of(), List.of("합성 다음")),
                0,
                0,
                0,
                0,
                0,
                now.minusSeconds(60));
        reported = checks.save(reported);
        silent = ProactiveCheck.started(
                owner.id(), career.id(), checkConversation.id(), CheckTrigger.SCHEDULED, false, now.minusSeconds(30));
        silent.succeed(CheckOutcome.NOTHING_NEW, 0, 0, null, 0, 0, 0, 0, 0, now.minusSeconds(20));
        silent = checks.save(silent);
    }

    @AfterEach
    void tearDown() {
        for (Long userId : List.of(owner.id(), other.id())) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memory_revision WHERE memory_id IN (SELECT id FROM memory WHERE owner_user_id = ?)", userId);
            jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", userId);
            jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("보고, 할 일 제안과 반응, 관심 범위 Memory 가 그 살펴보기의 결정 하나로 묶이고 침묵은 보고 없는 상황으로 남는다")
    void linksCareerPilotFlowToOneDecision() {
        checkFeedback.ended(reported);
        checkFeedback.ended(silent);
        AgentExecution child = executions.save(execution(root.id(), Instant.now()));
        followUps.propose(owner, checkConversation.id(), child.id(), FIRST_TITLE, null, false);
        followUps.propose(owner, checkConversation.id(), root.id(), SECOND_TITLE, null, false);
        Map<String, FollowUpSnapshot> byTitle =
                followUps.list(owner).stream().collect(Collectors.toMap(FollowUpSnapshot::title, Function.identity()));
        FollowUpSnapshot first = byTitle.get(FIRST_TITLE);
        followUps.update(owner, first.publicId(), new FollowUpPatch(null, false, null, true));
        followUps.accept(owner, first.publicId());
        followUps.done(owner, first.publicId());
        followUps.reject(owner, byTitle.get(SECOND_TITLE).publicId());
        Memory narrowed = memories.proposeUser(owner, "합성 관심 범위", "합성 분야의 공고만 본다", root.id());
        memories.accept(owner, narrowed.id());

        DecisionFeedbackExport export = exporter.export(owner, Duration.ofDays(30));

        DecisionRecord decision = record(export, "check:" + reported.id());
        assertThat(decision.situation().reportSurfaced()).isTrue();
        Map<String, Subject> subjects =
                decision.subjects().stream().collect(Collectors.toMap(Subject::subjectKey, Function.identity()));
        assertThat(subjects.get("proactive_check:" + reported.id()).label()).isEqualTo(FeedbackLabel.NO_RESPONSE);
        Subject accepted = subjects.get("follow_up:" + first.publicId());
        assertThat(accepted.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(accepted.edited()).isTrue();
        assertThat(accepted.outcome()).isEqualTo(FeedbackEventType.EXECUTION_SUCCEEDED);
        assertThat(accepted.events())
                .extracting(DecisionFeedbackExport.Event::type)
                .containsExactly(
                        FeedbackEventType.SURFACED,
                        FeedbackEventType.EDITED,
                        FeedbackEventType.ACCEPTED,
                        FeedbackEventType.EXECUTION_SUCCEEDED);
        assertThat(accepted.events().get(1).changedFields()).containsExactly("WAITING");
        Subject rejected = subjects.get("follow_up:" + byTitle.get(SECOND_TITLE).publicId());
        assertThat(rejected.label()).isEqualTo(FeedbackLabel.DECLINED);
        assertThat(rejected.persistentPreference()).isFalse();
        Subject memory = subjects.get("memory:" + narrowed.id());
        assertThat(memory.label()).isEqualTo(FeedbackLabel.ACCEPTED);
        assertThat(memory.persistentPreference()).isTrue();

        DecisionRecord silence = record(export, "check:" + silent.id());
        assertThat(silence.situation().reportSurfaced()).isFalse();
        assertThat(silence.situation().outcome()).isEqualTo(CheckOutcome.NOTHING_NEW);
        assertThat(silence.subjects()).isEmpty();

        assertThat(export.toString()).doesNotContain(FIRST_TITLE, SECOND_TITLE, "합성 분야의 공고만 본다");
        assertThat(exporter.export(other, Duration.ofDays(30)).records()).isEmpty();
    }

    @Test
    @DisplayName("살펴보기 밖의 대화에서 나온 할 일 제안은 그 제안의 열쇠로 따로 묶이고 대화를 지우면 사건이 함께 지워진다")
    void forgetsEventsWhenConversationIsDeleted() {
        Conversation plain = conversations.save(Conversation.startedBy(owner.id(), "합성 대화", career.id(), Instant.now()));
        AgentExecution turn = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .conversationId(plain.id())
                .agentId(career.id())
                .profileName(career.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        followUps.propose(owner, plain.id(), turn.id(), FIRST_TITLE, null, false);
        FollowUpSnapshot proposed = followUps.list(owner).getFirst();
        followUps.accept(owner, proposed.publicId());
        String key = FeedbackSubjectType.FOLLOW_UP.key(proposed.publicId());

        assertThat(record(exporter.export(owner, Duration.ofDays(30)), key).situation()).isNull();
        assertThat(subjectEvents(key)).hasSize(2).allSatisfy(event -> {
            assertThat(event.conversationId()).isEqualTo(plain.id());
            assertThat(event.originExecutionId()).isEqualTo(turn.id());
        });

        chat.delete(owner, plain.id());

        assertThat(subjectEvents(key)).isEmpty();
        assertThat(exporter.export(owner, Duration.ofDays(30)).records())
                .extracting(DecisionRecord::decisionKey)
                .doesNotContain(key);
    }

    @Test
    @DisplayName("점검 대화를 지우면 그 살펴보기의 상황과 제안이 replay 읽기 모델에서 빠진다")
    void hidesDeletedCheckConversation() {
        checkFeedback.ended(reported);
        followUps.propose(owner, checkConversation.id(), root.id(), FIRST_TITLE, null, false);

        chat.delete(owner, checkConversation.id());

        assertThat(exporter.export(owner, Duration.ofDays(30)).records()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM decision_feedback_event WHERE user_id = ?", Long.class, owner.id()))
                .isZero();
    }

    private List<FeedbackEvent> subjectEvents(String key) {
        return events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(owner.id(), List.of(key));
    }

    private static DecisionRecord record(DecisionFeedbackExport export, String decisionKey) {
        return export.records().stream()
                .filter(each -> each.decisionKey().equals(decisionKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record " + decisionKey + " in " + export.records()));
    }

    private AgentExecution execution(Long rootId, Instant now) {
        return AgentExecution.builder()
                .userId(owner.id())
                .conversationId(checkConversation.id())
                .agentId(career.id())
                .parentExecutionId(rootId)
                .rootExecutionId(rootId)
                .profileName(career.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(now)
                .build();
    }

    private CurrentUser member(Instant now) {
        String email = "decision-feedback-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, now));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
