package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.purge.application.ConversationPurger;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.followup.application.dto.NewFollowUp;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.followup.infra.FollowUpSuggestionBodies;
import com.bifos.assistant.proactive.check.application.CheckFeedback;
import com.bifos.assistant.proactive.check.application.CheckFindingReactions;
import com.bifos.assistant.proactive.check.application.LegacySuggestionBackfill;
import com.bifos.assistant.proactive.check.application.dto.BackfillReport;
import com.bifos.assistant.proactive.check.application.dto.CheckFindingView;
import com.bifos.assistant.proactive.check.application.dto.FindingReaction;
import com.bifos.assistant.proactive.check.domain.CheckReport;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.check.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.check.domain.ProblemEvidence;
import com.bifos.assistant.proactive.check.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.FindingReason;
import com.bifos.assistant.proactive.check.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.check.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 옛 발견과 문제 후보를 할 일의 {@code PROPOSED} 줄로 옮기는 작업을 실제 DB 로 본다(ADR-20261101 / proactive-suggestion 의 옮기는
 * 순서 4). 옛 줄은 지우지 않고, 반응 사건은 옮긴 줄이 있을 때만 그 줄을 가리킨다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
class LegacySuggestionBackfillTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    LegacySuggestionBackfill backfill;

    @Autowired
    CheckFindingReactions reactions;

    @Autowired
    CheckFeedback checkFeedback;

    @Autowired
    FollowUpService followUps;

    @Autowired
    FollowUpRepository followUpRows;

    @Autowired
    FollowUpSuggestionBodies bodies;

    @Autowired
    FeedbackEventRepository events;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckFindingRepository findings;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatService chat;

    @Autowired
    ConversationPurger purger;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    TestClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private CurrentUser other;
    private Conversation conversation;
    private AgentExecution root;
    private ProactiveCheck check;
    private Agent agent;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        owner = member();
        other = member();
        String code = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
        agent = agents.save(Agent.of(
            code,
            "합성 분야",
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        conversation = conversations.save(Conversation.startedForCheck(owner.id(), "합성 살펴보기", agent.id(), NOW));
        root = executions.save(AgentExecution.builder()
            .userId(owner.id())
            .conversationId(conversation.id())
            .agentId(agent.id())
            .profileName(agent.hermesProfile())
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(NOW)
            .build());
        check = reportedCheck();
    }


    @AfterEach
    void tearDown() {
        for (Long userId : List.of(owner.id(), other.id())) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update(
                "DELETE FROM proactive_check_problem WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
            jdbc.update(
                "DELETE FROM proactive_check_finding WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                userId);
            jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", owner.id());
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", owner.id(), other.id());
    }


    @Test
    @DisplayName("새로 알릴 발견과 받아들인 문제 후보가 옛 줄과 같은 사용자·대화·실행의 PROPOSED 줄이 되고 참고 발견과 버린 후보는 옮기지 않는다")
    void migratesShownFindingsAndAcceptedProblemsWithOwnerConversationAndExecution() {
        ProactiveCheckFinding shown = finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        ProactiveCheckFinding reference = finding(FindingKind.REFERENCE, null, "합성 참고");
        ProactiveCheckProblem accepted = problem(ProblemStatus.ACCEPTED, "career:apply", "합성 지원 문제", "합성 지원서를 보낸다");
        ProactiveCheckProblem dropped = problem(ProblemStatus.DROPPED, "career:old", "합성 버린 문제", "합성 버린 행동");

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        List<FollowUp> rows = rowsOf(owner);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(FollowUpStatus.PROPOSED);
            assertThat(row.userId()).isEqualTo(owner.id());
            assertThat(row.conversationId()).isEqualTo(conversation.id());
            assertThat(row.proposedByExecutionId()).isEqualTo(root.id());
            assertThat(row.expiresAt()).isAfter(NOW);
        });
        FollowUp fromFinding = byKey("legacy:proactive_check_finding:" + shown.id());
        assertThat(fromFinding.title()).isEqualTo("합성 공고 마감");
        assertThat(detailOf(fromFinding))
            .contains("\"legacy\"", "\"proactive_check_finding\"", "\"career:deadline\"", "\"NEW\"")
            .doesNotContain("합성 참고");
        FollowUp fromProblem = byKey("legacy:proactive_check_problem:" + accepted.id());
        assertThat(fromProblem.title()).as("할 일 제목은 행동 글이다").isEqualTo("합성 지원서를 보낸다");
        assertThat(detailOf(fromProblem))
            .contains("\"legacy\"", "\"career:apply\"", "\"합성 지원 문제\"", "\"HIGH\"", "\"https://example.com/evidence\"");
        assertThat(rows.stream().map(FollowUp::dedupeKey))
            .doesNotContain(
                "legacy:proactive_check_finding:" + reference.id(), "legacy:proactive_check_problem:" + dropped.id());
        assertThat(findings.count()).as("옛 줄은 지우지 않는다").isGreaterThanOrEqualTo(2);
        assertThat(problems.findById(dropped.id())).isPresent();
    }


    @Test
    @DisplayName("옛 반응 사건은 옮긴 제안 줄의 열쇠를 가리키고 옛 번호로 읽은 반응과 목록의 모양은 그대로다")
    void repointsReactionEventsAndKeepsOldIdAndResponseShape() {
        ProactiveCheckFinding shown = finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        ProactiveCheckFinding untouched = finding(FindingKind.NEW, null, "합성 행사 신청");
        checkFeedback.ended(check);
        reactions.react(owner, shown.id(), FindingReaction.DISMISSED);
        assertThat(eventsUnder(FeedbackSubjectType.CHECK_FINDING.key(shown.id()))).hasSize(2);

        BackfillReport report = backfill.backfill();

        FollowUp moved = byKey("legacy:proactive_check_finding:" + shown.id());
        String newKey = FeedbackSubjectType.FOLLOW_UP.key(moved.publicId());
        assertThat(report.repointed()).as("보였다 + 관심 없음 + 다른 발견의 보였다").isEqualTo(3);
        assertThat(eventsUnder(FeedbackSubjectType.CHECK_FINDING.key(shown.id()))).isEmpty();
        assertThat(eventsUnder(newKey))
            .extracting(FeedbackEvent::eventType)
            .containsExactly(FeedbackEventType.SURFACED, FeedbackEventType.DISMISSED);
        assertThat(eventsUnder(newKey)).allSatisfy(event -> {
            assertThat(event.subjectType()).isEqualTo(FeedbackSubjectType.FOLLOW_UP);
            assertThat(event.conversationId()).isEqualTo(conversation.id());
            assertThat(event.originExecutionId()).isEqualTo(root.id());
            assertThat(event.sourceCheckId()).isEqualTo(check.id());
        });
        assertThat(reactions.current(owner.id(), List.of(shown.id(), untouched.id())))
            .containsOnlyKeys(shown.id())
            .containsEntry(shown.id(), FindingReaction.DISMISSED);
        List<CheckFindingView> listed = reactions.list(owner, conversation.publicId());
        assertThat(listed).extracting(CheckFindingView::id).containsExactly(shown.id(), untouched.id());
        assertThat(listed.getFirst().reaction()).isEqualTo(FindingReaction.DISMISSED);
        assertThat(listed.get(1).reaction()).isNull();
    }


    @Test
    @DisplayName("옛 번호로 들어온 새 반응은 옮긴 줄의 열쇠로 남고 지금 반응이 바뀐다")
    void reactionByOldIdAfterMigrationLandsOnMigratedRow() {
        ProactiveCheckFinding shown = finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        backfill.backfill();
        FollowUp moved = byKey("legacy:proactive_check_finding:" + shown.id());

        reactions.react(owner, shown.id(), FindingReaction.ACCEPTED);
        reactions.react(owner, shown.id(), FindingReaction.ACCEPTED);
        reactions.react(owner, shown.id(), FindingReaction.POSTPONED);

        assertThat(eventsUnder(FeedbackSubjectType.CHECK_FINDING.key(shown.id()))).isEmpty();
        assertThat(eventsUnder(FeedbackSubjectType.FOLLOW_UP.key(moved.publicId())))
            .extracting(FeedbackEvent::eventType)
            .containsExactly(FeedbackEventType.ACCEPTED, FeedbackEventType.POSTPONED);
        assertThat(reactions.current(owner.id(), List.of(shown.id())))
            .containsEntry(shown.id(), FindingReaction.POSTPONED);
    }


    @Test
    @DisplayName("같은 제목의 열린 할 일이 있으면 새 줄을 만들지 않고, 그 줄은 고치지 않으며 반응은 옛 열쇠에 남는다")
    void sameTitleOpenFollowUpIsNotDuplicatedOrTouchedAndReactionStaysLegacy() {
        FollowUpSnapshot mine = followUps.create(owner, new NewFollowUp("합성 공고 마감", null, false, null));
        ProactiveCheckFinding shown = finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        reactions.react(owner, shown.id(), FindingReaction.DISMISSED);

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(rowsOf(owner)).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(mine.publicId());
            assertThat(row.status()).isEqualTo(FollowUpStatus.OPEN);
            assertThat(row.dedupeKey()).isNull();
            assertThat(row.detailJson()).isNull();
        });
        assertThat(eventsUnder(FeedbackSubjectType.CHECK_FINDING.key(shown.id()))).hasSize(1);
        assertThat(eventsUnder(FeedbackSubjectType.FOLLOW_UP.key(mine.publicId()))).isEmpty();
        assertThat(reactions.current(owner.id(), List.of(shown.id())))
            .containsEntry(shown.id(), FindingReaction.DISMISSED);
    }


    @Test
    @DisplayName("끝난 할 일 줄과 다른 사용자의 같은 제목은 옮기는 데 영향을 주지 않고 이미 있는 줄을 고치지 않는다")
    void closedFollowUpAndOtherUsersTitleDoNotBlockAndAreNotTouched() {
        FollowUpSnapshot done = followUps.create(owner, new NewFollowUp("합성 끝낸 일", null, false, null));
        followUps.done(owner, done.publicId());
        FollowUpSnapshot othersOpen = followUps.create(other, new NewFollowUp("합성 공고 마감", null, false, null));
        finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        finding(FindingKind.NEW, null, "합성 끝낸 일");

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).isEqualTo(2);
        assertThat(rowsOf(owner)).hasSize(3);
        assertThat(rowsOf(owner).stream().filter(row -> row.dedupeKey() == null)).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(done.publicId());
            assertThat(row.status()).isEqualTo(FollowUpStatus.DONE);
        });
        assertThat(rowsOf(other)).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(othersOpen.publicId());
            assertThat(row.dedupeKey()).isNull();
        });
    }


    @Test
    @DisplayName("같은 대화에서 30일 안에 거절한 제목은 옮기지 않는다")
    void recentlyRejectedTitleIsNotMigrated() {
        FollowUp rejected = followUpRows.saveAndFlush(FollowUp.proposed(
            owner.id(), conversation.id(), root.id(), "합성 거절한 제안", FollowUpService.titleKey("합성 거절한 제안"), null, false, NOW));
        rejected.reject(NOW.minus(Duration.ofDays(3)));
        followUpRows.saveAndFlush(rejected);
        finding(FindingKind.NEW, null, "합성 거절한 제안");

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).isZero();
        assertThat(rowsOf(owner)).singleElement().satisfies(row -> assertThat(row.status()).isEqualTo(FollowUpStatus.REJECTED));
    }


    @Test
    @DisplayName("두 번 돌려도 제안 수가 같고 다시 돌린 쪽은 이미 옮긴 줄로 센다")
    void runningTwiceKeepsSameCount() {
        finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        problem(ProblemStatus.ACCEPTED, "career:apply", "합성 지원 문제", "합성 지원서를 보낸다");

        BackfillReport first = backfill.backfill();
        long afterFirst = rowsOf(owner).size();
        BackfillReport second = backfill.backfill();

        assertThat(first.created()).isEqualTo(2);
        assertThat(second.created()).isZero();
        assertThat(second.existing()).isEqualTo(2);
        assertThat(rowsOf(owner)).hasSize((int)afterFirst);
        assertThat(rowsOf(owner).stream().map(FollowUp::dedupeKey).distinct()).hasSize(2);
    }


    @Test
    @DisplayName("중간에 멈춘 뒤 다시 돌리면 빠진 줄과 옮기지 못한 반응을 이어서 옮기고 이미 옮긴 줄은 그대로다")
    void resumesAfterInterruption() {
        ProactiveCheckFinding first = finding(FindingKind.NEW, "career:one", "합성 첫째");
        ProactiveCheckFinding second = finding(FindingKind.NEW, "career:two", "합성 둘째");
        ProactiveCheckFinding third = finding(FindingKind.NEW, "career:three", "합성 셋째");
        for (ProactiveCheckFinding each : List.of(first, second, third)) {
            reactions.react(owner, each.id(), FindingReaction.DISMISSED);
        }
        backfill.backfill();
        FollowUp keptRow = byKey("legacy:proactive_check_finding:" + first.id());
        // 둘째는 줄을 만들기 전에, 셋째는 줄을 만든 뒤 반응을 옮기기 전에 멈춘 상태로 되돌린다.
        FollowUp secondRow = byKey("legacy:proactive_check_finding:" + second.id());
        jdbc.update("DELETE FROM follow_up WHERE id = ?", secondRow.id());
        jdbc.update(
            "UPDATE decision_feedback_event SET subject_type = 'CHECK_FINDING', subject_key = ? WHERE subject_key = ?",
            FeedbackSubjectType.CHECK_FINDING.key(second.id()),
            FeedbackSubjectType.FOLLOW_UP.key(secondRow.publicId()));
        FollowUp thirdRow = byKey("legacy:proactive_check_finding:" + third.id());
        jdbc.update(
            "UPDATE decision_feedback_event SET subject_type = 'CHECK_FINDING', subject_key = ? WHERE subject_key = ?",
            FeedbackSubjectType.CHECK_FINDING.key(third.id()),
            FeedbackSubjectType.FOLLOW_UP.key(thirdRow.publicId()));

        BackfillReport resumed = backfill.backfill();

        assertThat(resumed.created()).isEqualTo(1);
        assertThat(resumed.existing()).isEqualTo(2);
        assertThat(rowsOf(owner)).hasSize(3);
        assertThat(byKey("legacy:proactive_check_finding:" + first.id()).id()).isEqualTo(keptRow.id());
        assertThat(byKey("legacy:proactive_check_finding:" + third.id()).id()).isEqualTo(thirdRow.id());
        for (ProactiveCheckFinding each : List.of(first, second, third)) {
            assertThat(eventsUnder(FeedbackSubjectType.CHECK_FINDING.key(each.id()))).as("finding=%s", each.id()).isEmpty();
            assertThat(reactions.current(owner.id(), List.of(each.id())))
                .containsEntry(each.id(), FindingReaction.DISMISSED);
        }
    }


    @Test
    @DisplayName("7일이 지난 옛 줄은 만료된 제안으로 옮겨 목록에 보이지 않고 같은 제목의 새 제안과 다른 옛 줄을 막지 않는다")
    void expiredLegacyRowsAreMigratedAsExpiredAndDoNotBlock() {
        ProactiveCheckFinding oldOne = finding(FindingKind.NEW, "career:old-one", "합성 오래된 공고");
        ProactiveCheckFinding oldTwo = finding(FindingKind.NEW, "career:old-two", "합성 오래된 공고");
        ProactiveCheckFinding recent = finding(FindingKind.NEW, "career:recent", "합성 최근 공고");
        age(oldOne, Duration.ofDays(20));
        age(oldTwo, Duration.ofDays(10));
        age(recent, Duration.ofDays(1));

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).as("만료된 줄도 옛 번호와 이어지도록 옮긴다").isEqualTo(3);
        assertThat(followUps.list(owner)).singleElement().satisfies(each -> assertThat(each.title()).isEqualTo("합성 최근 공고"));
        FollowUp expired = byKey("legacy:proactive_check_finding:" + oldOne.id());
        assertThat(expired.status()).isEqualTo(FollowUpStatus.PROPOSED);
        assertThat(expired.openMarker()).as("만료된 줄은 열린 줄에서 빠진다").isNull();
        assertThat(expired.createdAt()).isBefore(NOW.minus(Duration.ofDays(7)));
        assertThat(byKey("legacy:proactive_check_finding:" + oldTwo.id()).openMarker()).isNull();
    }


    @Test
    @DisplayName("점검 대화를 지우면 옮긴 제안 줄과 본문과 반응 사건이 남지 않고 받아들인 줄은 본문만 비운다")
    void deletingConversationErasesMigratedSuggestionsAndTheirEvents() {
        ProactiveCheckFinding shown = finding(FindingKind.NEW, "career:deadline", "합성 공고 마감");
        ProactiveCheckFinding accepted = finding(FindingKind.NEW, "career:event", "합성 행사 신청");
        problem(ProblemStatus.ACCEPTED, "career:apply", "합성 지원 문제", "합성 지원서를 보낸다");
        reactions.react(owner, shown.id(), FindingReaction.DISMISSED);
        backfill.backfill();
        FollowUp toAccept = byKey("legacy:proactive_check_finding:" + accepted.id());
        followUps.accept(owner, toAccept.publicId());
        assertThat(rowsOf(owner)).hasSize(3);

        chat.delete(owner, conversation.id());
        assertThat(purger.purgeDue(clock.instant())).isGreaterThanOrEqualTo(1);

        assertThat(rowsOf(owner)).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(toAccept.publicId());
            assertThat(row.status()).isEqualTo(FollowUpStatus.OPEN);
            assertThat(row.detailJson()).as("옮긴 제안의 본문").isNull();
            assertThat(row.detailKeyId()).isNull();
        });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM decision_feedback_event WHERE user_id = ? AND subject_key LIKE 'follow_up:%'"
                + " AND conversation_id = ?",
            Long.class,
            owner.id(),
            conversation.id())).isZero();
    }


    @Test
    @DisplayName("제목이 빈 발견은 건너뛰고 나머지는 옮긴다")
    void blankTitleIsSkippedAndOthersAreMigrated() {
        ProactiveCheckFinding good = finding(FindingKind.NEW, "career:good", "합성 정상 발견");
        ProactiveCheckFinding blank = finding(FindingKind.NEW, "career:blank", "   ");

        BackfillReport report = backfill.backfill();

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.skipped()).as("제목이 빈 발견은 제안 줄이 될 수 없어 건너뛴다").isEqualTo(1);
        assertThat(rowsOf(owner)).singleElement().satisfies(row -> assertThat(row.title()).isEqualTo("합성 정상 발견"));
        assertThat(rowsOf(owner).stream().map(FollowUp::dedupeKey))
            .doesNotContain("legacy:proactive_check_finding:" + blank.id())
            .contains("legacy:proactive_check_finding:" + good.id());
    }


    private ProactiveCheck reportedCheck() {
        ProactiveCheck started = ProactiveCheck.started(
            owner.id(), agent.id(), conversation.id(), CheckTrigger.SCHEDULED, false, NOW.minusSeconds(120));
        started.attachRoot(root.id(), "session-fixture");
        started.succeed(
            CheckOutcome.FINDINGS,
            2,
            1,
            new CheckReport(List.of("합성 변화"), List.of(), List.of(), List.of(), List.of("합성 다음")),
            0,
            0,
            0,
            0,
            0,
            NOW.minusSeconds(60));
        return checks.save(started);
    }


    private ProactiveCheckFinding finding(FindingKind kind, String topicKey, String title) {
        return findings.save(ProactiveCheckFinding.of(
            check.id(),
            conversation.id(),
            kind,
            kind == FindingKind.REFERENCE ? FindingReason.NO_SOURCE : null,
            "career",
            topicKey,
            title,
            kind == FindingKind.NEW ? "https://example.com/" + UUID.randomUUID() : null,
            NOW));
    }


    private ProactiveCheckProblem problem(ProblemStatus status, String problemKey, String problem, String action) {
        return problems.save(ProactiveCheckProblem.of(
            check.id(),
            conversation.id(),
            status,
            status == ProblemStatus.DROPPED ? ProblemDropReason.NO_GOAL : null,
            problemKey,
            problem,
            "합성 목표",
            "ACTION",
            action,
            "HIGH",
            "합성 기대 이익",
            "NONE",
            null,
            null,
            List.of(new ProblemEvidence("career:deadline", "https://example.com/evidence", NOW)),
            NOW));
    }


    /** 옛 줄이 생긴 시각을 그만큼 과거로 옮긴다. */
    private void age(ProactiveCheckFinding target, Duration age) {
        jdbc.update(
            "UPDATE proactive_check_finding SET created_at = ? WHERE id = ?",
            Timestamp.from(NOW.minus(age).truncatedTo(ChronoUnit.MICROS)),
            target.id());
    }


    private List<FollowUp> rowsOf(CurrentUser user) {
        return followUpRows.findAll().stream().filter(row -> row.userId().equals(user.id())).toList();
    }


    private FollowUp byKey(String dedupeKey) {
        return followUpRows.findByUserIdAndDedupeKey(owner.id(), dedupeKey).orElseThrow();
    }


    private String detailOf(FollowUp row) {
        return bodies.open(owner.id(), row.dedupeKey(), row.detailJson(), row.detailKeyId()).orElseThrow();
    }


    private List<FeedbackEvent> eventsUnder(String subjectKey) {
        return events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(owner.id(), List.of(subjectKey));
    }


    private CurrentUser member() {
        String email = "legacy-suggestion-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
