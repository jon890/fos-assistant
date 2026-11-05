package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.proactive.check.application.ProactiveCheckService;
import com.bifos.assistant.proactive.check.domain.CheckReport;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.ReportedFinding;
import com.bifos.assistant.proactive.check.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.check.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.check.domain.type.CheckStatus;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.FindingReason;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기가 쓰는 저장소 쿼리를 본다. 점검 대화 찾기, session 별 살펴보기 수, 최근 발견 읽기다.
 *
 * <p>저장소는 트랜잭션을 열지 않으므로 호출을 {@link TransactionTemplate} 안에서 한다. 검사들이 H2 를 함께 써서, 이 검사가 만든 줄이 다른
 * 검사에 보이지 않도록 앞뒤로 지운다. 사용자와 에이전트 번호는 다른 검사와 겹치지 않는 값을 쓴다.
 */
@BackendIntegrationTest
class ProactiveCheckRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final long USER = 930_001L;
    private static final long OTHER_USER = 930_002L;
    private static final long AGENT = 930_101L;
    private static final long CONVERSATION = 930_201L;
    private static final long OTHER_CONVERSATION = 930_202L;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ProactiveCheckService service;

    @Autowired
    TestClock contextClock;

    private final List<Long> createdConversations = new ArrayList<>();


    @BeforeEach
    void setUp() {
        clearProactiveRows();
    }


    @AfterEach
    void tearDown() {
        clearProactiveRows();
        transactions.executeWithoutResult(status -> conversations.deleteAllById(createdConversations));
    }


    @Test
    @DisplayName("보고 읽음 표시는 소유권을 검사하고 첫 시각과 판정 재료를 담은 보고 JSON 을 유지한다")
    void reportOpeningChecksOwnershipAndKeepsFirstTimestamp() {
        ProactiveCheck check = saveCheck(CONVERSATION, "report-session", 77L);
        CheckReport report = new CheckReport(
            List.of("변화"),
            List.of("확인"),
            List.of(),
            List.of(),
            List.of("다음"),
            List.of(new ReportedFinding("trend:topic", "https://example.com/topic", "제목")),
            List.of("trend:problem"));
        transactions.executeWithoutResult(status -> checks.findById(check.id())
            .orElseThrow()
            .succeed(CheckOutcome.FINDINGS, report, 2, 0, NOW.plusSeconds(10)));
        CurrentUser other = new CurrentUser(OTHER_USER, "other@example.com", "다른 사용자", 1L, UserRole.MEMBER);
        CurrentUser owner = new CurrentUser(USER, "owner@example.com", "주인", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> service.openReport(other, check.id()))
            .isInstanceOfSatisfying(
                ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.PROACTIVE_CHECK_NOT_FOUND));
        assertThat(checks.findById(check.id()).orElseThrow().reportOpenedAt()).isNull();

        service.openReport(owner, check.id());
        Instant opened = checks.findById(check.id()).orElseThrow().reportOpenedAt();
        service.openReport(owner, check.id());

        ProactiveCheck stored = checks.findById(check.id()).orElseThrow();
        assertThat(stored.reportOpenedAt()).isEqualTo(opened);
        assertThat(stored.report()).isEqualTo(report);
    }


    @Test
    @DisplayName("점검 대화 찾기는 지운 대화와 보통 대화와 다른 사용자의 대화를 고르지 않는다")
    void findsOnlyLiveCheckConversationOfThatUserAndAgent() {
        Conversation expected = save(Conversation.startedForCheck(USER, "점검", AGENT, NOW));
        Conversation deleted = save(Conversation.startedForCheck(USER, "지운 점검", AGENT, NOW));
        transactions.executeWithoutResult(status -> conversations.deleteIfActive(deleted.id(), USER, NOW));
        save(Conversation.startedBy(USER, "보통", AGENT, NOW));
        save(Conversation.startedForCheck(OTHER_USER, "남의 점검", AGENT, NOW));

        Optional<Conversation> found = transactions.execute(
            status -> conversations.findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
                USER, AGENT, ConversationPurpose.CHECK));

        assertThat(found).as("고른 점검 대화").map(Conversation::id).contains(expected.id());
        assertThat(found.orElseThrow().purpose()).isEqualTo(ConversationPurpose.CHECK);
    }


    @Test
    @DisplayName("점검 대화가 지운 것뿐이면 찾지 못한다")
    void findsNothingWhenOnlyDeletedCheckConversationExists() {
        Conversation deleted = save(Conversation.startedForCheck(USER, "지운 점검", AGENT, NOW));
        transactions.executeWithoutResult(status -> conversations.deleteIfActive(deleted.id(), USER, NOW));
        save(Conversation.startedBy(USER, "보통", AGENT, NOW));

        Optional<Conversation> found = transactions.execute(
            status -> conversations.findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
                USER, AGENT, ConversationPurpose.CHECK));

        assertThat(found).isEmpty();
    }


    @Test
    @DisplayName("살펴보기 수는 점검 대화와 루트 session 이 모두 같은 줄만 센다")
    void countsChecksPerConversationAndRootSession() {
        saveCheck(CONVERSATION, "session-a", 1L);
        saveCheck(CONVERSATION, "session-a", 2L);
        saveCheck(CONVERSATION, "session-b", 3L);
        saveCheck(OTHER_CONVERSATION, "session-a", 4L);

        assertThat(count(CONVERSATION, "session-a")).as("같은 대화의 session-a").isEqualTo(2);
        assertThat(count(CONVERSATION, "session-b")).as("같은 대화의 session-b").isEqualTo(1);
        assertThat(count(OTHER_CONVERSATION, "session-a"))
            .as("다른 대화의 session-a")
            .isEqualTo(1);
        assertThat(count(CONVERSATION, "session-c")).as("보낸 적 없는 session").isZero();
    }


    @Test
    @DisplayName("지난 살펴보기는 그 대화에서 넘긴 상태가 아니고 모델 없이 건너뛰지 않은 마지막 줄이다")
    void findsLastCheckOfConversationWhoseStatusIsNotGiven() {
        ProactiveCheck finished = saveCheck(CONVERSATION, "session-a", 11L);
        transactions.executeWithoutResult(status -> {
            ProactiveCheck row = checks.findById(finished.id()).orElseThrow();
            row.succeed(CheckOutcome.NOTHING_NEW, null, 2, 0, NOW.plusSeconds(60));
        });
        ProactiveCheck skipped = saveCheck(CONVERSATION, null, null);
        transactions.executeWithoutResult(status -> {
            ProactiveCheck row = checks.findById(skipped.id()).orElseThrow();
            row.skip(CheckSkippedReason.UNREAD_REPORT, NOW.plusSeconds(120));
        });
        saveCheck(CONVERSATION, "session-a", 12L);
        saveCheck(OTHER_CONVERSATION, "session-a", 13L);

        Optional<ProactiveCheck> last = transactions.execute(
            status -> checks.findFirstByConversationIdAndStatusNotAndSkippedReasonIsNullOrderByIdDesc(
                CONVERSATION, CheckStatus.RUNNING));

        assertThat(last).map(ProactiveCheck::id).contains(finished.id());
        assertThat(last.orElseThrow().outcome()).isEqualTo(CheckOutcome.NOTHING_NEW);
        assertThat(last.orElseThrow().toolCalls()).isEqualTo(2);
    }


    @Test
    @DisplayName("최근 보고 읽기는 그 대화에서 기간 안에 시작해 보고를 남긴 살펴보기만 최근 것부터 돌려준다")
    void readsRecentReportedChecksOfConversation() {
        Instant after = NOW.minus(Duration.ofDays(30));
        ProactiveCheck old = reportedCheck(CONVERSATION, after.minusSeconds(1));
        ProactiveCheck boundary = reportedCheck(CONVERSATION, after);
        ProactiveCheck first = reportedCheck(CONVERSATION, after.plusSeconds(1));
        ProactiveCheck second = reportedCheck(CONVERSATION, NOW);
        ProactiveCheck silent = saveCheck(CONVERSATION, "session-a", 21L);
        ProactiveCheck elsewhere = reportedCheck(OTHER_CONVERSATION, NOW);

        List<ProactiveCheck> recent = transactions.execute(
            status -> checks.findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc(CONVERSATION, after));

        assertThat(recent)
            .as("기간의 시작 시각과 같은 줄, 보고 없는 줄, 다른 대화는 빠진다")
            .extracting(ProactiveCheck::id)
            .containsExactly(second.id(), first.id())
            .doesNotContain(old.id(), boundary.id(), silent.id(), elsewhere.id());
    }


    private Conversation save(Conversation conversation) {
        Conversation saved = transactions.execute(status -> conversations.save(conversation));
        createdConversations.add(saved.id());
        return saved;
    }


    private ProactiveCheck saveCheck(long conversationId, String rootSessionId, Long rootExecutionId) {
        ProactiveCheck check = ProactiveCheck.started(USER, AGENT, conversationId, CheckTrigger.MANUAL, false, NOW);
        check.attachRoot(rootExecutionId, rootSessionId);
        return transactions.execute(status -> checks.save(check));
    }


    /** 그 시각에 시작해 보고를 남긴 살펴보기 줄이다. */
    private ProactiveCheck reportedCheck(long conversationId, Instant startedAt) {
        ProactiveCheck check = ProactiveCheck.started(USER, AGENT, conversationId, CheckTrigger.MANUAL, false, startedAt);
        check.succeed(
            CheckOutcome.FINDINGS,
            new CheckReport(List.of("변화"), List.of(), List.of(), List.of(), List.of()),
            0,
            0,
            startedAt.plusSeconds(10));
        return transactions.execute(status -> checks.save(check));
    }


    private long count(long conversationId, String rootSessionId) {
        return transactions.execute(
            status -> checks.countByConversationIdAndHermesRootSessionId(conversationId, rootSessionId));
    }


    private void clearProactiveRows() {
        transactions.executeWithoutResult(status -> {
            checks.deleteAll();
        });
    }
}
