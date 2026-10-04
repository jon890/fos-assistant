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
import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionFollowUpRef;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 할 일이 나를 기다리는 카드에 어떻게 올라오는지 실제 DB 로 본다. 규칙은 {@code docs/backend/attention.md} 의 「후보와 trigger」 다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자는 검사마다 새로 만들고 끝나면 그 사용자의 줄을 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FollowUpAttentionSourceTest.FixedClock.class)
class FollowUpAttentionSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final TestClock CLOCK = new TestClock(NOW);
    private static final String TITLE = "학교 알림장 확인";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock followUpAttentionTestClock() {
            return CLOCK;
        }
    }

    @MockitoBean
    HermesConnectorClient connector;

    @Autowired
    AttentionService service;

    @Autowired
    AttentionControlService controls;

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
    FollowUpRepository followUps;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private Agent chief;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        when(connector.readCatalog()).thenReturn(List.of());
        dad = member();
        chief = agentOf(dad);
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM attention_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM attention_control WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM connector_action WHERE user_id = ?", userId);
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
    @DisplayName("제안은 나를 기다리는 카드에 LATER 로 보이고 건수에 들지 않는다")
    void showsProposalAsLaterAndDoesNotCount() {
        FollowUp proposed = followUps.save(FollowUp.proposed(
                dad.id(),
                null,
                7391L,
                TITLE,
                FollowUpService.titleKey(TITLE),
                NOW.plus(Duration.ofHours(1)),
                false,
                NOW));

        AttentionView view = service.view(dad);

        AttentionItem item = onlyItem(view);
        assertThat(item.itemKey()).isEqualTo("follow_up:" + proposed.publicId());
        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.trigger()).isEqualTo(AttentionTrigger.FOLLOW_UP_PROPOSED);
        assertThat(item.followUp().proposed()).isTrue();
        assertThat(view.nowCount()).as("기한이 코앞이어도 제안은 건수에 들지 않는다").isZero();
    }

    @Test
    @DisplayName("기한이 10시간 뒤인 OPEN 은 DUE_SOON 으로 NOW 이고 followUp 칸을 채운다")
    void showsDueSoonAsNow() {
        Instant dueAt = NOW.plus(Duration.ofHours(10));
        FollowUp open = open("기한이 다가온 일", dueAt, false, null, NOW.minusSeconds(60));

        AttentionView view = service.view(dad);

        AttentionItem item = onlyItem(view);
        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.trigger()).isEqualTo(AttentionTrigger.FOLLOW_UP_OPEN);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.DUE_SOON);
        assertThat(item.followUp()).isEqualTo(new AttentionFollowUpRef(open.publicId(), dueAt, false, false));
        assertThat(view.nowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("기한이 지난 OPEN 은 OVERDUE 로 NOW 다")
    void showsOverdueAsNow() {
        open("기한이 지난 일", NOW.minusSeconds(60), false, null, NOW.minus(Duration.ofDays(1)));

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.OVERDUE);
    }

    @Test
    @DisplayName("기한이 없고 waiting 이 참인 OPEN 은 WAITING 으로 LATER 다")
    void showsWaitingWithoutDueAsLater() {
        open("답을 기다리는 일", null, true, null, NOW.minusSeconds(60));

        AttentionView view = service.view(dad);

        AttentionItem item = onlyItem(view);
        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.WAITING);
        assertThat(view.nowCount()).isZero();
    }

    @Test
    @DisplayName("기한이 지났고 waiting 이 참이면 신호 순서는 OVERDUE, WAITING 이다")
    void ordersSignalsOverdueBeforeWaiting() {
        open("지나서 기다리는 일", NOW.minusSeconds(60), true, null, NOW.minus(Duration.ofDays(1)));

        assertThat(onlyItem(service.view(dad)).why().signals())
                .containsExactly(AttentionSignal.OVERDUE, AttentionSignal.WAITING);
    }

    @Test
    @DisplayName("연결한 대화의 위임 실행 결과가 받아들인 뒤에 전해졌으면 LINKED_UPDATE 로 NOW 다")
    void showsLinkedUpdateFromDelegation() {
        Conversation conversation = conversationOf(dad);
        open("위임 결과를 기다리는 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));
        delegationDelivered(conversation, NOW.minus(Duration.ofHours(1)));

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.LINKED_UPDATE);
        assertThat(item.conversationId()).isEqualTo(conversation.publicId());
        assertThat(item.at()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("연결한 대화의 승인 줄 결과가 받아들인 뒤에 전해졌으면 LINKED_UPDATE 로 NOW 다")
    void showsLinkedUpdateFromApproval() {
        Conversation conversation = conversationOf(dad);
        open("승인 결과를 기다리는 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));
        actionDelivered(dad, conversation, NOW.minus(Duration.ofHours(1)));

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(item.why().signals()).containsExactly(AttentionSignal.LINKED_UPDATE);
    }

    @Test
    @DisplayName("받아들이기 전에 전해진 결과는 LINKED_UPDATE 가 아니다")
    void ignoresDeliveryBeforeAcceptance() {
        Conversation conversation = conversationOf(dad);
        delegationDelivered(conversation, NOW.minus(Duration.ofHours(3)));
        open("이미 지난 결과의 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.why().signals()).isEmpty();
    }

    @Test
    @DisplayName("받아들인 뒤 사용자와 어시스턴트 메시지와 승인 만료 알림만 있는 대화는 LINKED_UPDATE 가 아니다")
    void ignoresConversationMessagesWithoutDelivery() {
        Conversation conversation = conversationOf(dad);
        open("대화만 오간 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));
        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "어떻게 됐어?", NOW.minus(Duration.ofHours(1))));
        messages.save(ChatMessage.fromAssistant(conversation.id(), "확인해 볼게요", null, NOW.minus(Duration.ofMinutes(50))));
        messages.save(ChatMessage.fromSystem(conversation.id(), "승인 요청이 만료됐어요", NOW.minus(Duration.ofMinutes(40))));

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.why().signals()).isEmpty();
    }

    @Test
    @DisplayName("연결한 대화를 지웠으면 할 일은 남고 conversationId 는 null 이며 결과 도착을 보지 않는다")
    void keepsFollowUpOfDeletedConversationWithoutLinkedUpdate() {
        Conversation conversation = conversationOf(dad);
        open("지운 대화의 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));
        delegationDelivered(conversation, NOW.minus(Duration.ofHours(1)));
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), conversation.id());

        AttentionItem item = onlyItem(service.view(dad));

        assertThat(item.level()).isEqualTo(AttentionLevel.LATER);
        assertThat(item.conversationId()).isNull();
        assertThat(item.why().signals()).isEmpty();
    }

    @Test
    @DisplayName("숨긴 할 일은 대화에 메시지가 더 와도 나오지 않고 새 결과가 전해지면 다시 나온다")
    void hiddenFollowUpReturnsOnNewDelivery() {
        Conversation conversation = conversationOf(dad);
        FollowUp open = open("숨길 일", null, false, conversation, NOW.minus(Duration.ofHours(2)));
        AttentionItem shown = onlyItem(service.view(dad));
        controls.hide(dad, CardKey.NEEDS_ME, shown.itemKey(), shown.stateKey());

        assertThat(card(service.view(dad)).items()).isEmpty();

        messages.save(ChatMessage.fromAssistant(conversation.id(), "확인해 볼게요", null, NOW.minus(Duration.ofMinutes(50))));
        messages.save(ChatMessage.fromSystem(conversation.id(), "승인 요청이 만료됐어요", NOW.minus(Duration.ofMinutes(40))));
        assertThat(card(service.view(dad)).items()).as("메시지가 더 와도 상태가 같다").isEmpty();

        delegationDelivered(conversation, NOW.minus(Duration.ofMinutes(30)));
        AttentionItem again = onlyItem(service.view(dad));
        assertThat(again.itemKey()).isEqualTo("follow_up:" + open.publicId());
        assertThat(again.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(again.why().signals()).containsExactly(AttentionSignal.LINKED_UPDATE);
    }

    @Test
    @DisplayName("기한이 3일 뒤라 LATER 일 때 숨긴 할 일은 시계가 기한 10시간 전이 되면 DUE_SOON 으로 다시 나온다")
    void hiddenFollowUpReturnsWhenDueSoon() {
        Instant dueAt = NOW.plus(Duration.ofDays(3));
        open("기한이 먼 일", dueAt, false, null, NOW.minusSeconds(60));
        AttentionItem shown = onlyItem(service.view(dad));
        assertThat(shown.level()).isEqualTo(AttentionLevel.LATER);
        controls.hide(dad, CardKey.NEEDS_ME, shown.itemKey(), shown.stateKey());
        assertThat(card(service.view(dad)).items()).isEmpty();

        CLOCK.set(dueAt.minus(Duration.ofHours(10)));

        AttentionItem again = onlyItem(service.view(dad));
        assertThat(again.level()).isEqualTo(AttentionLevel.NOW);
        assertThat(again.why().signals()).containsExactly(AttentionSignal.DUE_SOON);
    }

    @Test
    @DisplayName("끝낸 할 일은 응답에 없다")
    void hidesDoneFollowUp() {
        FollowUp open = open("끝낼 일", NOW.minusSeconds(60), false, null, NOW.minus(Duration.ofDays(1)));
        assertThat(card(service.view(dad)).items()).hasSize(1);
        open.done(NOW);
        followUps.save(open);

        assertThat(card(service.view(dad)).items()).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 할 일은 응답에 없다")
    void hidesOtherUsersFollowUp() {
        CurrentUser child = member();
        followUps.save(FollowUp.opened(
                dad.id(), null, "아빠의 일", FollowUpService.titleKey("아빠의 일"), NOW.minusSeconds(60), false, NOW));

        AttentionView view = service.view(child);

        assertThat(card(view).items()).isEmpty();
        assertThat(view.nowCount()).isZero();
    }

    /** 받아들인 시각을 {@code acceptedAt} 으로 둔 OPEN 할 일 하나를 저장한다. */
    private FollowUp open(String title, Instant dueAt, boolean waiting, Conversation conversation, Instant acceptedAt) {
        return followUps.save(FollowUp.opened(
                dad.id(),
                conversation == null ? null : conversation.id(),
                title,
                FollowUpService.titleKey(title),
                dueAt,
                waiting,
                acceptedAt));
    }

    /** 위임 실행 하나를 저장하고 그 결과를 전한 시각을 적는다. */
    private void delegationDelivered(Conversation conversation, Instant deliveredAt) {
        AgentExecution delegation = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .delegationKey("follow-up-attention-" + UUID.randomUUID())
                .profileName(chief.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(deliveredAt.minusSeconds(60), deliveredAt)
                .build());
        jdbc.update(
                "UPDATE agent_execution SET result_delivered_at = ? WHERE id = ?",
                Timestamp.from(deliveredAt),
                delegation.id());
    }

    /** 끝나서 결과를 대화에 전한 승인 줄 하나를 넣는다. 승인 줄을 만드는 길은 커넥터 검사가 본다. */
    private void actionDelivered(CurrentUser owner, Conversation conversation, Instant deliveredAt) {
        jdbc.update(
                """
                INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                    approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key,
                    args_json, args_sha256, expires_at, created_at, result_delivered_at)
                VALUES (?, ?, ?, 'follow-up-notes', 'write_note', 'mcp__demo__write_note', 'WRITE', 'REQUIRED',
                    'NEEDS_APPROVAL', TRUE, 'SUCCEEDED', 0, ?, ?, '{"text":"안녕"}', 'sha', ?, ?, ?)
                """,
                bytes(UUID.randomUUID()),
                owner.id(),
                chief.id(),
                conversation.id(),
                UUID.randomUUID().toString(),
                Timestamp.from(NOW.plus(Duration.ofHours(3))),
                Timestamp.from(NOW.minus(Duration.ofHours(2))),
                Timestamp.from(deliveredAt));
    }

    private CurrentUser member() {
        String email = "follow-up-attention-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner) {
        String code = "follow-up-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                "집안일 도우미",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    private Conversation conversationOf(CurrentUser owner) {
        return conversations.save(
                Conversation.startedBy(owner.id(), "할 일에 이어진 대화", chief.id(), NOW.minusSeconds(3_600)));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static AttentionCard card(AttentionView view) {
        return view.cards().stream()
                .filter(card -> card.key() == CardKey.NEEDS_ME)
                .findFirst()
                .orElseThrow(() -> new AssertionError("나를 기다리는 카드가 응답에 없다: " + view.cards()));
    }

    private static AttentionItem onlyItem(AttentionView view) {
        List<AttentionItem> items = card(view).items();
        assertThat(items).as("나를 기다리는 카드의 항목").hasSize(1);
        return items.getFirst();
    }

    /** 검사가 정한 시각만 주는 시계다. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
