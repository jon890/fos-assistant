package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.chat.application.model.OpenedCheck;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.TurnSlot;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 점검 대화를 찾고 만드는 동작을 실제 저장소로 본다.
 *
 * <p>사용자 자리가 없을 때를 보려고 동시 실행 한도를 2 로 두고 자리를 모두 쥔다. 자리를 쥐지 않은 검사는 한도에 걸리지 않는다. 검사들이 H2 를 함께 쓰므로 사용자
 * 번호는 다른 검사와 겹치지 않는 값을 쓰고, 만든 줄은 끝날 때 지운다.
 */
@BackendIntegrationTest
@TestPropertySource(properties = "assistant.user-execution.max-running=2")
class CheckConversationsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    CheckConversations checkConversations;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentRepository agents;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    TransactionTemplate transactions;

    private final CurrentUser user = new CurrentUser(931_001L, "check@example.com", "점검", 1L, UserRole.MEMBER);
    private final List<Long> createdAgents = new ArrayList<>();
    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = agents.save(Agent.of(
                "check-" + UUID.randomUUID(),
                "커리어",
                "check-profile-" + UUID.randomUUID(),
                "http://agent-runtime.test/p/check",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                NOW));
        createdAgents.add(agent.id());
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> conversations.deleteAll(checkConversationsOf(true)));
        agents.deleteAllById(createdAgents);
    }

    /** 그 사용자의 자리를 한도만큼 모두 쥔다. */
    private List<TurnSlot> holdAllSlots() {
        return List.of(limiter.acquireTurn(user.id()), limiter.acquireTurn(user.id()));
    }

    /** 그 사용자와 에이전트의 점검 대화. 지운 것까지 넣을지 고른다. */
    private List<Conversation> checkConversationsOf(boolean withDeleted) {
        return conversations.findAll().stream()
                .filter(c -> user.id().equals(c.userId()) && agent.id().equals(c.agentId()))
                .filter(c -> c.purpose() == ConversationPurpose.CHECK)
                .filter(c -> withDeleted || c.deletedAt() == null)
                .toList();
    }

    @Test
    @DisplayName("점검 대화가 없으면 만들고, 있으면 같은 것을 돌려준다")
    void createsWhenAbsentThenReturnsSameOne() {
        OpenedCheck first = checkConversations.findOrCreate(user, agent);
        OpenedCheck second = checkConversations.findOrCreate(user, agent);

        assertThat(first.created()).as("처음 부를 때 만든다").isTrue();
        Conversation created = first.conversation();
        assertThat(created.purpose()).isEqualTo(ConversationPurpose.CHECK);
        assertThat(created.title()).isEqualTo("먼저 살펴보기 · 커리어");
        assertThat(created.userId()).isEqualTo(user.id());
        assertThat(created.agentId()).isEqualTo(agent.id());
        assertThat(second.created()).as("두 번째는 만들지 않는다").isFalse();
        assertThat(second.conversation().id()).as("같은 점검 대화").isEqualTo(created.id());
        assertThat(checkConversations.find(user.id(), agent.id()))
                .map(Conversation::id)
                .contains(created.id());
    }

    @Test
    @DisplayName("지운 점검 대화는 다시 쓰지 않고 새로 만든다")
    void deletedCheckConversationIsNotReused() {
        Long deletedId =
                checkConversations.findOrCreate(user, agent).conversation().id();
        transactions.executeWithoutResult(status -> conversations.deleteIfActive(deletedId, user.id(), NOW));

        OpenedCheck reopened = checkConversations.findOrCreate(user, agent);

        assertThat(reopened.created()).isTrue();
        assertThat(reopened.conversation().id()).as("새 점검 대화").isNotEqualTo(deletedId);
    }

    @Test
    @DisplayName("두 스레드가 동시에 불러도 점검 대화는 하나만 생긴다")
    void concurrentCallsCreateOnlyOne() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<OpenedCheck>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return checkConversations.findOrCreate(user, agent);
                }));
            }
            start.countDown();
            OpenedCheck one = results.get(0).get(10, TimeUnit.SECONDS);
            OpenedCheck other = results.get(1).get(10, TimeUnit.SECONDS);

            assertThat(checkConversationsOf(false)).as("만든 점검 대화").hasSize(1);
            assertThat(one.conversation().id()).isEqualTo(other.conversation().id());
            assertThat(List.of(one.created(), other.created())).as("한쪽만 만들었다").containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("사용자 자리가 없으면 점검 대화를 만들지 않고 USER_BUSY 다")
    void rejectsWithoutCreatingWhenUserHasNoRoom() {
        List<TurnSlot> held = holdAllSlots();
        try {
            assertThatThrownBy(() -> checkConversations.findOrCreate(user, agent))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.USER_BUSY);
            assertThat(checkConversationsOf(true)).as("만든 점검 대화").isEmpty();
        } finally {
            held.forEach(TurnSlot::release);
        }
    }

    @Test
    @DisplayName("이미 있는 점검 대화는 사용자 자리가 없어도 돌려준다")
    void returnsExistingEvenWhenUserHasNoRoom() {
        Long existingId =
                checkConversations.findOrCreate(user, agent).conversation().id();
        List<TurnSlot> held = holdAllSlots();
        try {
            OpenedCheck found = checkConversations.findOrCreate(user, agent);

            assertThat(found.created()).isFalse();
            assertThat(found.conversation().id()).isEqualTo(existingId);
        } finally {
            held.forEach(TurnSlot::release);
        }
    }

    @Test
    @DisplayName("방금 만든 점검 대화를 지우면 다시 찾지 못한다")
    void deleteCreatedRemovesConversation() {
        Long createdId =
                checkConversations.findOrCreate(user, agent).conversation().id();

        checkConversations.deleteCreated(createdId);

        assertThat(conversations.findById(createdId)).isEmpty();
        assertThat(checkConversations.find(user.id(), agent.id())).isEmpty();
    }
}
