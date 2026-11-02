package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.dto.AttentionCard;
import com.bifos.assistant.attention.application.dto.AttentionItem;
import com.bifos.assistant.attention.application.dto.AttentionView;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackCleaner;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 지금 화면의 숨김, 미루기, 복원을 반응 기록({@code decision_feedback_event})으로 본다. 현재 제어는 카드와 항목마다 마지막 제어
 * 사건에서 구하고, 보관 기간이 지나도 마지막 숨김은 남아야 한다.
 * 계약은 {@code docs/features/attention.md} 의 「사용자 제어」 와 「저장」 이다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자는 검사마다 새로 만들고 끝나면 그 사용자의 줄을 지운다.
 */
@BackendIntegrationTest
class AttentionReactionControlTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

    @Autowired
    TestClock clock;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    AttentionService attention;

    @Autowired
    AttentionControlService controls;

    @Autowired
    ReactionLedger ledger;

    @Autowired
    DecisionFeedbackCleaner cleaner;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private Agent chief;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        when(connector.readCatalog()).thenReturn(List.of());
        dad = member();
        chief = agentOf(dad);
    }


    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
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
    @DisplayName("같은 항목이 두 카드에 있어도 카드마다 숨김과 미루기가 서로 독립이다")
    void keepsControlsIndependentPerCard() {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        // 같은 대화가 앞선 카드에 있으면 이어서 하기 카드에는 나오지 않는다. 실패 카드에서 숨기면 이어서 하기에 보인다.
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());
        assertThat(items(CardKey.FAILURES)).isEmpty();
        AttentionItem recent = onlyItem(attention.view(dad), CardKey.CONTINUE);
        assertThat(recent.itemKey()).isEqualTo(failure.itemKey());

        controls.snooze(dad, CardKey.CONTINUE, recent.itemKey(), NOW.plus(Duration.ofHours(3)));
        assertThat(items(CardKey.FAILURES)).isEmpty();
        assertThat(items(CardKey.CONTINUE)).isEmpty();

        controls.restore(dad, CardKey.FAILURES, failure.itemKey());
        assertThat(items(CardKey.FAILURES)).hasSize(1);
        assertThat(ledger.currentControls(dad.id()))
            .extracting(record -> record.cardKey() + ":" + record.type())
            .containsExactlyInAnyOrder("FAILURES:RESTORED", "CONTINUE:POSTPONED");
    }


    @Test
    @DisplayName("숨긴 항목은 상태가 바뀌면 다시 보이고 같은 상태이면 계속 숨는다")
    void showsHiddenItemAgainWhenStateChanges() {
        Conversation conversation = failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());
        assertThat(items(CardKey.FAILURES)).isEmpty();

        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "한 번 더 해 줘", NOW.minusSeconds(120)));
        executions.save(root(conversation, NOW.minusSeconds(120), NOW.minusSeconds(60)));

        AttentionItem again = onlyItem(attention.view(dad), CardKey.FAILURES);
        assertThat(again.stateKey()).isNotEqualTo(failure.stateKey());
    }


    @Test
    @DisplayName("미루기는 기한이 지나면 다시 보이고 복원은 바로 보인다")
    void snoozeExpiresAndRestoreShowsAtOnce() {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        Instant until = NOW.plus(Duration.ofHours(5));

        controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), until);
        assertThat(items(CardKey.FAILURES)).isEmpty();
        clock.set(until.minusSeconds(1));
        assertThat(items(CardKey.FAILURES)).as("기한 직전에는 미뤄져 있다").isEmpty();
        clock.set(until.plusSeconds(1));
        assertThat(items(CardKey.FAILURES)).as("기한 뒤에는 다시 보인다").hasSize(1);

        clock.set(NOW);
        controls.snooze(dad, CardKey.FAILURES, failure.itemKey(), until);
        controls.restore(dad, CardKey.FAILURES, failure.itemKey());
        assertThat(items(CardKey.FAILURES)).as("복원은 기한을 기다리지 않는다").hasSize(1);
    }


    @Test
    @DisplayName("같은 요청 키의 재전송은 사건 하나이고 숨김, 복원, 숨김은 키가 달라 모두 남는다")
    void mergesResendsByRequestKeyButKeepsNewOperations() {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "req-1");
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "req-1");
        assertThat(controlEvents()).containsExactly("DISMISSED");

        controls.restore(dad, CardKey.FAILURES, failure.itemKey(), "req-2");
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "req-3");
        assertThat(controlEvents()).containsExactly("DISMISSED", "RESTORED", "DISMISSED");
        assertThat(items(CardKey.FAILURES)).isEmpty();

        controls.restore(dad, CardKey.FAILURES, failure.itemKey(), "req-2");
        assertThat(controlEvents()).as("지난 복원 요청을 다시 보내도 다시 풀리지 않는다").hasSize(3);
        assertThat(items(CardKey.FAILURES)).isEmpty();
    }


    @Test
    @DisplayName("요청 키가 같아도 동작이 다르면 다른 사건이다")
    void treatsSameRequestKeyOfOtherOperationAsNewEvent() {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);

        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "same");
        controls.restore(dad, CardKey.FAILURES, failure.itemKey(), "same");

        assertThat(controlEvents()).containsExactly("DISMISSED", "RESTORED");
        assertThat(items(CardKey.FAILURES)).hasSize(1);
    }


    @Test
    @DisplayName("같은 요청이 동시에 와도 사건은 하나이고 모두 성공한다")
    void mergesConcurrentResends() throws Exception {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> {
                    controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "burst");
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(calls)) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(controlEvents()).containsExactly("DISMISSED");
        assertThat(items(CardKey.FAILURES)).isEmpty();
    }


    @Test
    @DisplayName("숨김과 복원이 동시에 와도 현재 제어는 마지막으로 들어간 사건과 같다")
    void keepsCurrentControlEqualToLastEventUnderConcurrency() throws Exception {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int turn = i;
                calls.add(() -> {
                    if (turn % 2 == 0) {
                        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey(), "h-" + turn);
                    } else {
                        controls.restore(dad, CardKey.FAILURES, failure.itemKey(), "r-" + turn);
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(calls)) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> events = controlEvents();
        String last = events.getLast();
        assertThat(items(CardKey.FAILURES)).hasSize("RESTORED".equals(last) ? 1 : 0);
    }


    @Test
    @DisplayName("보관 기간이 지나 정리해도 마지막 숨김은 남고 풀린 사건과 만료된 미루기는 지운다")
    void keepsLastHideAfterRetention() {
        failedConversation();
        AttentionItem failure = onlyItem(attention.view(dad), CardKey.FAILURES);
        controls.hide(dad, CardKey.FAILURES, failure.itemKey(), failure.stateKey());
        AttentionItem recent = onlyItem(attention.view(dad), CardKey.CONTINUE);
        controls.snooze(dad, CardKey.CONTINUE, recent.itemKey(), NOW.plus(Duration.ofHours(3)));

        int deleted = cleaner.clean(NOW.plus(Duration.ofDays(400)));

        assertThat(deleted).as("노출 사건 같은 나머지는 지운다").isPositive();
        assertThat(ledger.currentControls(dad.id()))
            .extracting(record -> record.cardKey() + ":" + record.type())
            .as("숨김은 남고 기한이 지난 미루기는 남지 않는다")
            .containsExactly("FAILURES:DISMISSED");
        assertThat(items(CardKey.FAILURES)).as("정리 뒤에도 숨겨져 있다").isEmpty();
    }


    /** 요청자의 제어 사건(숨김, 미루기, 복원)의 종류를 일어난 순서대로 읽는다. */
    private List<String> controlEvents() {
        return jdbc.queryForList(
            "SELECT event_type FROM decision_feedback_event WHERE user_id = ? AND card_key IS NOT NULL ORDER BY id",
            String.class,
            dad.id());
    }


    private List<AttentionItem> items(CardKey key) {
        AttentionView view = attention.view(dad);
        AttentionCard card = view.cards().stream().filter(c -> c.key() == key).findFirst().orElseThrow();
        return card.items();
    }


    private static AttentionItem onlyItem(AttentionView view, CardKey key) {
        List<AttentionItem> items = view.cards().stream()
            .filter(card -> card.key() == key)
            .findFirst()
            .orElseThrow()
            .items();
        assertThat(items).as("카드 %s 의 항목", key).hasSize(1);
        return items.getFirst();
    }


    /** 사용자 글 뒤에 실패한 루트 실행이 하나 있는 대화다. */
    private Conversation failedConversation() {
        Conversation conversation = conversations.save(Conversation.startedBy(dad.id(), "주간 장보기 목록 정리", chief.id(), NOW.minusSeconds(3_600)));
        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "목록 정리해 줘", NOW.minusSeconds(600)));
        executions.save(root(conversation, NOW.minusSeconds(600), NOW.minusSeconds(540)));
        return conversation;
    }


    private AgentExecution root(Conversation conversation, Instant started, Instant finished) {
        return AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(chief.id())
            .profileName(chief.hermesProfile())
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.FAILED)
            .timing(started, finished)
            .build();
    }


    private CurrentUser member() {
        String email = "attention-reaction-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private Agent agentOf(CurrentUser owner) {
        String code = "attention-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
            code,
            "집안일 도우미",
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
    }
}
