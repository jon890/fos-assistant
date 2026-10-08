package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.proactive.application.CheckFeedback;
import com.bifos.assistant.proactive.application.CheckFindingReactions;
import com.bifos.assistant.proactive.application.model.CheckFindingView;
import com.bifos.assistant.proactive.application.model.FindingReaction;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 점검 대화의 「새로 알릴 것」 발견에 누른 단추가 판단 피드백 사건으로 남고, 발견 목록이 지금 반응을 싣는지 본다. 남의 대화와 남의 발견은 없는
 * 것과 같은 응답으로 숨는다. 합성 데이터만 쓴다.
 *
 * <p>{@code digest-window} 를 36시간으로 두어 「관심 없음」 기간이 하루 단위로 올림되는지도 본다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.proactive-check.digest-window=36h")
class CheckFindingReactionsTest {

    /** {@code Long} 캐시(-128~127) 밖의 번호다. 주인 판정이 참조가 아니라 값으로 견주는지 본다. */
    private static final long LARGE_USER_ID = 1_000_000_123L;

    @Autowired
    CheckFindingReactions reactions;

    @Autowired
    CheckFeedback checkFeedback;

    @Autowired
    FeedbackEventRepository events;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckFindingRepository findings;

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
    private Conversation ownerConversation;
    private Conversation otherConversation;
    private AgentExecution ownerRoot;
    private ProactiveCheck ownerCheck;
    private ProactiveCheckFinding firstNew;
    private ProactiveCheckFinding secondNew;
    private ProactiveCheckFinding reference;
    private ProactiveCheckFinding othersFinding;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        deleteLeftoverLargeIdMember();
        owner = largeIdMember();
        other = member(now);
        String code = "finding-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = agents.save(Agent.of(
                code,
                "합성 분야",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                now));
        ownerConversation = conversations.save(Conversation.startedForCheck(owner.id(), "합성 살펴보기", agent.id(), now));
        otherConversation = conversations.save(Conversation.startedForCheck(other.id(), "합성 살펴보기", agent.id(), now));

        ownerRoot = executions.save(execution(owner, ownerConversation, agent, now));
        ownerCheck = reportedCheck(owner, ownerConversation, agent, ownerRoot, now);
        firstNew = finding(ownerCheck, FindingKind.NEW, null, "career:first", "합성 공고 마감", now);
        reference = finding(ownerCheck, FindingKind.REFERENCE, FindingReason.NO_SOURCE, null, "합성 참고", now);
        secondNew = finding(ownerCheck, FindingKind.NEW, null, null, "합성 행사 신청", now);

        AgentExecution otherRoot = executions.save(execution(other, otherConversation, agent, now));
        ProactiveCheck otherCheck = reportedCheck(other, otherConversation, agent, otherRoot, now);
        othersFinding = finding(otherCheck, FindingKind.NEW, null, "career:other", "합성 남의 발견", now);
    }

    @AfterEach
    void tearDown() {
        deleteRowsOf(owner.id());
        deleteRowsOf(other.id());
        jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", owner.id());
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", owner.id(), other.id());
    }

    @Test
    @DisplayName("「관심 없음」 을 누르면 사용자 사건이 점검 대화와 살펴보기 번호와 함께 남고 목록의 반응이 관심 없음이다")
    void recordsDismissedAsUserFeedbackEvent() {
        reactions.react(owner, firstNew.id(), FindingReaction.DISMISSED);

        List<FeedbackEvent> recorded = eventsOf(firstNew);
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.subjectType()).isEqualTo(FeedbackSubjectType.CHECK_FINDING);
            assertThat(event.subjectKey()).isEqualTo("check_finding:" + firstNew.id());
            assertThat(event.eventType()).isEqualTo(FeedbackEventType.DISMISSED);
            assertThat(event.actor()).isEqualTo(FeedbackActor.USER);
            assertThat(event.userId()).isEqualTo(owner.id());
            assertThat(event.conversationId()).isEqualTo(ownerConversation.id());
            assertThat(event.originExecutionId()).isEqualTo(ownerRoot.id());
            assertThat(event.sourceCheckId()).isEqualTo(ownerCheck.id());
        });
        assertThat(reactionOf(firstNew)).isEqualTo(FindingReaction.DISMISSED);
        assertThat(reactionOf(secondNew)).isNull();
    }

    @Test
    @DisplayName("같은 단추를 다시 누르면 사건이 늘지 않고, 「나중에」 로 바꾸면 지금 반응이 나중에다")
    void ignoresSameReactionAndKeepsLastChange() {
        reactions.react(owner, firstNew.id(), FindingReaction.DISMISSED);
        reactions.react(owner, firstNew.id(), FindingReaction.DISMISSED);

        assertThat(eventsOf(firstNew)).hasSize(1);

        reactions.react(owner, firstNew.id(), FindingReaction.POSTPONED);

        assertThat(eventsOf(firstNew))
                .extracting(FeedbackEvent::eventType)
                .containsExactly(FeedbackEventType.DISMISSED, FeedbackEventType.POSTPONED);
        assertThat(reactionOf(firstNew)).isEqualTo(FindingReaction.POSTPONED);
        assertThat(reactions.current(owner.id(), List.of(firstNew.id())))
                .containsExactlyEntriesOf(Map.of(firstNew.id(), FindingReaction.POSTPONED));
    }

    @Test
    @DisplayName("남의 발견과 「참고」 발견, 없는 발견은 살펴보기 없음으로 거절하고 사건을 남기지 않는다")
    void rejectsOthersReferenceAndMissingFindings() {
        assertNotFound(() -> reactions.react(owner, othersFinding.id(), FindingReaction.ACCEPTED));
        assertNotFound(() -> reactions.react(owner, reference.id(), FindingReaction.ACCEPTED));
        assertNotFound(() -> reactions.react(owner, Long.MAX_VALUE, FindingReaction.ACCEPTED));

        assertThat(eventsOf(othersFinding)).isEmpty();
        assertThat(eventsOf(reference)).isEmpty();
    }

    @Test
    @DisplayName("모르는 반응 값과 빈 값은 검증 실패다")
    void rejectsUnknownReactionValue() {
        assertThat(FindingReaction.parse("DISMISSED")).isEqualTo(FindingReaction.DISMISSED);
        for (String value : new String[] {"IGNORED", "dismissed", "", null}) {
            assertThatThrownBy(() -> FindingReaction.parse(value))
                    .as("reaction=%s", value)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("목록은 남의 대화 공개 식별자를 대화 없음으로 거절하고 「참고」 발견을 싣지 않는다")
    void listsOnlyOwnNewFindings() {
        assertThatThrownBy(() -> reactions.list(owner, otherConversation.publicId()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));

        List<CheckFindingView> listed = reactions.list(owner, ownerConversation.publicId());

        assertThat(listed).extracting(CheckFindingView::id).containsExactly(firstNew.id(), secondNew.id());
        assertThat(listed.getFirst()).satisfies(view -> {
            assertThat(view.checkId()).isEqualTo(ownerCheck.id());
            assertThat(view.executionId()).isEqualTo(ownerRoot.id());
            assertThat(view.area()).isEqualTo("career");
            assertThat(view.topicKey()).isEqualTo("career:first");
            assertThat(view.title()).isEqualTo("합성 공고 마감");
            assertThat(view.reaction()).isNull();
        });
        assertThat(listed.get(1).topicKey()).isNull();
    }

    @Test
    @DisplayName("사용자 번호가 127 보다 커도 주인 판정이 값으로 맞는다")
    void matchesOwnerByValueForLargeUserId() {
        assertThat(owner.id()).isGreaterThan(127L);
        // 저장소에서 다시 읽은 번호와 요청자의 번호가 다른 객체여도 같은 주인으로 본다.
        CurrentUser sameOwner = new CurrentUser(
                Long.valueOf(LARGE_USER_ID), owner.email(), owner.displayName(), owner.groupId(), owner.role());

        reactions.react(sameOwner, secondNew.id(), FindingReaction.ACCEPTED);

        assertThat(reactions.list(sameOwner, ownerConversation.publicId()))
                .extracting(CheckFindingView::id, CheckFindingView::reaction)
                .containsExactly(tuple(firstNew.id(), null), tuple(secondNew.id(), FindingReaction.ACCEPTED));
    }

    @Test
    @DisplayName("보고를 보인 살펴보기가 끝나면 「새로 알릴 것」 발견마다 시스템 사건으로 보였다를 남긴다")
    void recordsSurfacedForEachNewFinding() {
        checkFeedback.ended(ownerCheck);

        for (ProactiveCheckFinding shown : List.of(firstNew, secondNew)) {
            assertThat(eventsOf(shown))
                    .as("finding=%s", shown.id())
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.eventType()).isEqualTo(FeedbackEventType.SURFACED);
                        assertThat(event.actor()).isEqualTo(FeedbackActor.SYSTEM);
                        assertThat(event.conversationId()).isEqualTo(ownerConversation.id());
                        assertThat(event.originExecutionId()).isEqualTo(ownerRoot.id());
                        assertThat(event.sourceCheckId()).isEqualTo(ownerCheck.id());
                    });
        }
        assertThat(eventsOf(reference)).isEmpty();
        assertThat(reactionOf(firstNew)).isNull();
    }

    @Test
    @DisplayName("「관심 없음」 기간은 digest-window 를 하루 단위로 올림한 값이다")
    void roundsDismissWindowUpToDays() {
        assertThat(reactions.dismissWindowDays()).isEqualTo(2L);
    }

    private FindingReaction reactionOf(ProactiveCheckFinding target) {
        return reactions.list(owner, ownerConversation.publicId()).stream()
                .filter(view -> view.id().equals(target.id()))
                .findFirst()
                .orElseThrow()
                .reaction();
    }

    private List<FeedbackEvent> eventsOf(ProactiveCheckFinding target) {
        Long userId = target == othersFinding ? other.id() : owner.id();
        return events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(
                userId, List.of(FeedbackSubjectType.CHECK_FINDING.key(target.id())));
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PROACTIVE_CHECK_NOT_FOUND));
    }

    private ProactiveCheck reportedCheck(
            CurrentUser user, Conversation conversation, Agent agent, AgentExecution root, Instant now) {
        ProactiveCheck check = ProactiveCheck.started(
                user.id(), agent.id(), conversation.id(), CheckTrigger.SCHEDULED, false, now.minusSeconds(120));
        check.attachRoot(root.id(), "session-fixture");
        check.succeed(
                CheckOutcome.FINDINGS,
                2,
                1,
                new CheckReport(List.of("합성 변화"), List.of(), List.of(), List.of(), List.of("합성 다음")),
                0,
                0,
                0,
                0,
                0,
                now.minusSeconds(60));
        return checks.save(check);
    }

    private ProactiveCheckFinding finding(
            ProactiveCheck check, FindingKind kind, FindingReason reason, String topicKey, String title, Instant now) {
        return findings.save(ProactiveCheckFinding.of(
                check.id(),
                check.conversationId(),
                kind,
                reason,
                "career",
                topicKey,
                title,
                kind == FindingKind.NEW ? "https://example.com/" + UUID.randomUUID() : null,
                now,
                now));
    }

    private static AgentExecution execution(CurrentUser user, Conversation conversation, Agent agent, Instant now) {
        return AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(now)
                .build();
    }

    /** 그 사용자의 발견 반응 시험 줄을 지운다. 에이전트와 사용자 줄은 부르는 쪽이 지운다. */
    private void deleteRowsOf(Long userId) {
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
        jdbc.update(
                "DELETE FROM proactive_check_finding WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
    }

    /**
     * 앞 실행이 정리 전에 멈춰 남긴 큰 번호 사용자와 그 줄을 지운다. 번호를 직접 정해 넣으므로 남아 있으면 키가 겹친다. 그때의 다른 사용자 줄도
     * 이 사용자의 에이전트를 가리키므로 에이전트로 찾아 함께 지운다.
     */
    private void deleteLeftoverLargeIdMember() {
        String agentsOfUser = "SELECT id FROM agent WHERE owner_user_id = ?";
        jdbc.update(
                "DELETE FROM proactive_check_finding WHERE check_id IN"
                        + " (SELECT id FROM proactive_check WHERE agent_id IN (" + agentsOfUser + "))",
                LARGE_USER_ID);
        jdbc.update("DELETE FROM proactive_check WHERE agent_id IN (" + agentsOfUser + ")", LARGE_USER_ID);
        jdbc.update("DELETE FROM agent_execution WHERE agent_id IN (" + agentsOfUser + ")", LARGE_USER_ID);
        jdbc.update("DELETE FROM conversation WHERE agent_id IN (" + agentsOfUser + ")", LARGE_USER_ID);
        deleteRowsOf(LARGE_USER_ID);
        jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", LARGE_USER_ID);
        jdbc.update("DELETE FROM app_user WHERE id = ?", LARGE_USER_ID);
    }

    /** 번호를 직접 정해 넣는다. 저장소의 자동 번호로는 큰 번호를 만들 수 없다. */
    private CurrentUser largeIdMember() {
        String email = "check-finding-" + LARGE_USER_ID + "@example.com";
        jdbc.update(
                "INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                        + " VALUES (?, ?, ?, 1, 'MEMBER', CURRENT_TIMESTAMP(6))",
                LARGE_USER_ID,
                email,
                email);
        AppUser user = users.findById(LARGE_USER_ID).orElseThrow();
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private CurrentUser member(Instant now) {
        String email = "check-finding-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, now));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
