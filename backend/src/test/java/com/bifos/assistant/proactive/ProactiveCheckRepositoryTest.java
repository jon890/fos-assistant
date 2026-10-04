package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기가 쓰는 저장소 쿼리를 본다. 점검 대화 찾기, session 별 살펴보기 수, 최근 발견 읽기다.
 *
 * <p>저장소는 트랜잭션을 열지 않으므로 호출을 {@link TransactionTemplate} 안에서 한다. 검사들이 H2 를 함께 써서, 이 검사가 만든 줄이 다른
 * 검사에 보이지 않도록 앞뒤로 지운다. 사용자와 에이전트 번호는 다른 검사와 겹치지 않는 값을 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
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
    ProactiveCheckFindingRepository findings;

    @Autowired
    TransactionTemplate transactions;

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
    @DisplayName("점검 대화 찾기는 지운 대화와 보통 대화와 다른 사용자의 대화를 고르지 않는다")
    void findsOnlyLiveCheckConversationOfThatUserAndAgent() {
        Conversation expected = save(Conversation.startedForCheck(USER, "점검", AGENT, NOW));
        Conversation deleted = save(Conversation.startedForCheck(USER, "지운 점검", AGENT, NOW));
        transactions.executeWithoutResult(status -> conversations.deleteIfActive(deleted.id(), USER, NOW));
        save(Conversation.startedBy(USER, "보통", AGENT, NOW));
        save(Conversation.startedForCheck(OTHER_USER, "남의 점검", AGENT, NOW));

        Optional<Conversation> found = transactions.execute(status -> conversations
                .findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
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

        Optional<Conversation> found = transactions.execute(status -> conversations
                .findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
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
        assertThat(count(OTHER_CONVERSATION, "session-a")).as("다른 대화의 session-a").isEqualTo(1);
        assertThat(count(CONVERSATION, "session-c")).as("보낸 적 없는 session").isZero();
    }

    @Test
    @DisplayName("지난 살펴보기는 그 대화에서 넘긴 상태가 아닌 마지막 줄이다")
    void findsLastCheckOfConversationWhoseStatusIsNotGiven() {
        ProactiveCheck finished = saveCheck(CONVERSATION, "session-a", 11L);
        transactions.executeWithoutResult(status -> {
            ProactiveCheck row = checks.findById(finished.id()).orElseThrow();
            row.succeed(CheckOutcome.NOTHING_NEW, 0, 0, 2, 0, NOW.plusSeconds(60));
        });
        saveCheck(CONVERSATION, "session-a", 12L);
        saveCheck(OTHER_CONVERSATION, "session-a", 13L);

        Optional<ProactiveCheck> last = transactions.execute(
                status -> checks.findFirstByConversationIdAndStatusNotOrderByIdDesc(CONVERSATION, CheckStatus.RUNNING));

        assertThat(last).map(ProactiveCheck::id).contains(finished.id());
        assertThat(last.orElseThrow().outcome()).isEqualTo(CheckOutcome.NOTHING_NEW);
        assertThat(last.orElseThrow().toolCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("최근 발견 읽기는 그 대화와 종류와 기간으로 거르고 최근 것부터 주제 키와 함께 돌려준다")
    void readsRecentFindingsFilteredByConversationKindAndCreatedAt() {
        Instant after = NOW.minus(Duration.ofDays(30));
        ProactiveCheck check = saveCheck(CONVERSATION, "session-a", 21L);
        ProactiveCheck otherCheck = saveCheck(OTHER_CONVERSATION, "session-a", 22L);
        saveFinding(check, CONVERSATION, FindingKind.NEW, "topic-old", after.minusSeconds(1));
        saveFinding(check, CONVERSATION, FindingKind.NEW, "topic-boundary", after);
        ProactiveCheckFinding first = saveFinding(check, CONVERSATION, FindingKind.NEW, "topic-1", after.plusSeconds(1));
        ProactiveCheckFinding second = saveFinding(check, CONVERSATION, FindingKind.NEW, "topic-2", NOW);
        saveFinding(check, CONVERSATION, FindingKind.REFERENCE, "topic-reference", NOW);
        saveFinding(otherCheck, OTHER_CONVERSATION, FindingKind.NEW, "topic-other", NOW);

        List<ProactiveCheckFinding> recent = transactions.execute(
                status -> findings.findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(
                        CONVERSATION, FindingKind.NEW, after, PageRequest.of(0, 10)));
        List<ProactiveCheckFinding> limited = transactions.execute(
                status -> findings.findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(
                        CONVERSATION, FindingKind.NEW, after, PageRequest.of(0, 1)));
        List<ProactiveCheckFinding> notified = transactions.execute(
                status -> findings.findByConversationIdAndKindAndCreatedAtAfter(CONVERSATION, FindingKind.NEW, after));

        assertThat(recent)
                .as("기간 안의 NEW 발견. 기간의 시작 시각과 같은 줄은 빠진다")
                .extracting(ProactiveCheckFinding::id)
                .containsExactly(second.id(), first.id());
        assertThat(recent).extracting(ProactiveCheckFinding::topicKey).containsExactly("topic-2", "topic-1");
        assertThat(limited).as("쪽 크기 1").extracting(ProactiveCheckFinding::topicKey).containsExactly("topic-2");
        assertThat(notified)
                .as("이미 알린 묶음")
                .extracting(ProactiveCheckFinding::topicKey)
                .containsExactlyInAnyOrder("topic-1", "topic-2");
    }

    @Test
    @DisplayName("발견의 글이 칸 길이를 넘으면 칸 길이까지 잘라 저장한다")
    void truncatesFindingTextToColumnLength() {
        ProactiveCheck check = saveCheck(CONVERSATION, "session-a", 31L);
        String surrogatePair = "😀";
        // 120번째 글자 자리에서 대리 쌍이 나뉘므로 그 글자를 빼고 119자가 남는다.
        String titleSplittingPair = "가".repeat(119) + surrogatePair;
        ProactiveCheckFinding saved = transactions.execute(status -> findings.save(ProactiveCheckFinding.of(
                check.id(),
                CONVERSATION,
                FindingKind.REFERENCE,
                FindingReason.NO_SOURCE,
                "a".repeat(41),
                "k".repeat(121),
                titleSplittingPair,
                "https://example.com/" + "p".repeat(2000),
                null,
                NOW)));

        ProactiveCheckFinding read =
                transactions.execute(status -> findings.findById(saved.id()).orElseThrow());

        assertThat(read.area()).hasSize(40);
        assertThat(read.topicKey()).hasSize(120);
        assertThat(read.title()).isEqualTo("가".repeat(119));
        assertThat(read.sourceUrl()).hasSize(2000).startsWith("https://example.com/");
        assertThat(read.reason()).isEqualTo(FindingReason.NO_SOURCE);
        assertThat(read.checkedAt()).isNull();
    }

    private Conversation save(Conversation conversation) {
        Conversation saved = transactions.execute(status -> conversations.save(conversation));
        createdConversations.add(saved.id());
        return saved;
    }

    private ProactiveCheck saveCheck(long conversationId, String rootSessionId, long rootExecutionId) {
        ProactiveCheck check = ProactiveCheck.started(USER, AGENT, conversationId, CheckTrigger.MANUAL, NOW);
        check.attachRoot(rootExecutionId, rootSessionId);
        return transactions.execute(status -> checks.save(check));
    }

    private ProactiveCheckFinding saveFinding(
            ProactiveCheck check, long conversationId, FindingKind kind, String topicKey, Instant createdAt) {
        ProactiveCheckFinding finding = ProactiveCheckFinding.of(
                check.id(),
                conversationId,
                kind,
                kind == FindingKind.REFERENCE ? FindingReason.REPEATED : null,
                "trend",
                topicKey,
                "제목 " + topicKey,
                "https://example.com/" + topicKey,
                createdAt,
                createdAt);
        return transactions.execute(status -> findings.save(finding));
    }

    private long count(long conversationId, String rootSessionId) {
        return transactions.execute(
                status -> checks.countByConversationIdAndHermesRootSessionId(conversationId, rootSessionId));
    }

    private void clearProactiveRows() {
        transactions.executeWithoutResult(status -> {
            findings.deleteAll();
            checks.deleteAll();
        });
    }
}
