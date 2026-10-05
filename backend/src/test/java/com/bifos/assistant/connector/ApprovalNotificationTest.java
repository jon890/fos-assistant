package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorPolicyService;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.notification.application.NotificationEvent;
import com.bifos.assistant.notification.application.NotificationEventHub;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인이 필요한 커넥터 호출이 새 승인 줄을 만들 때와 그 줄이 만료될 때 알림을 남기는지 실제 DB 로 확인한다(ADR-070).
 *
 * <p>계약은 {@code docs/backend/notification.md} 의 「알림 종류」 다. 승인 카드가 뜰 대화가 있어야 알림이 생기므로 실제
 * 대화를 저장하고 실행이 그 대화 번호를 갖게 준비한다. 컨텍스트 수를 늘리지 않으려고 {@link ConnectorActionServiceTest} 와
 * 같은 구성을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ConnectorPolicyTestDoubles.class)
class ApprovalNotificationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = "approval-notice-owner";
    private static final String DEMO = "demo-notes";
    private static final String WRITE = "write_note";
    /** 승인을 늘 받는 도구다. 선언에 제목이 없다. */
    private static final String UNTITLED = "send_note";

    private static final String ARGS = "{\"text\":\"안녕\"}";

    private static final ConnectorManifest DECLARING = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "",
            List.of(),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null),
                    new ConnectorTool(UNTITLED, "WRITE", "always", null, null)));

    @Autowired
    ConnectorPolicyService policies;

    @Autowired
    ConnectorActionService service;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    NotificationEventHub hub;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    HermesConnectorClient connector;

    private final List<NotificationEvent> received = new CopyOnWriteArrayList<>();
    private Runnable subscription = () -> {};
    private AppUser owner;
    private CurrentUser me;
    private Agent agent;
    private Conversation conversation;
    private String root;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM connector_action");
        jdbc.update("DELETE FROM connector_tool_grant");
        notifications.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenReturn(List.of(DECLARING));
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.success(JSON.readTree("{\"saved\":true}")));

        owner = users.save(AppUser.of("approval-notice@example.com", "주인", 1L, UserRole.MEMBER, Instant.now()));
        me = new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        agent = Agent.of(
                "notice-" + UUID.randomUUID(),
                "검사용 메모",
                PROFILE,
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now());
        agent.markConnectorManaged();
        agent = agents.save(agent);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "승인 대화", agent.id(), Instant.now()));
        root = startRun(conversation.id());
        Instant connectedAt = Instant.parse("2026-10-01T00:00:00Z");
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, connectedAt);
        connection.ready(connectedAt);
        connection = connections.save(connection);
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, DECLARING.mcpServer(), connectedAt);
        binding.ready(connectedAt);
        bindings.save(binding);
        subscription = hub.subscribe(owner.id(), received::add);
    }

    @AfterEach
    void tearDown() {
        subscription.run();
        notifications.deleteAll();
    }

    @Test
    @DisplayName("승인 줄을 새로 만들면 그 대화를 가리키는 APPROVAL_REQUESTED 가 하나 생기고 커밋 뒤 created 사건이 간다")
    void newApprovalCreatesRequestedNotificationForItsConversation() {
        ConnectorPolicyAnswer answer = ask(root, WRITE, ARGS);

        Notification notice = onlyNotification();
        assertThat(answer.actionId()).isNotNull();
        assertThat(notice.userId()).isEqualTo(owner.id());
        assertThat(notice.kind()).isEqualTo(NotificationKind.APPROVAL_REQUESTED);
        assertThat(notice.title()).isEqualTo("승인을 기다리는 요청이 있어요");
        assertThat(notice.body()).isEqualTo("「메모 쓰기」");
        assertThat(notice.targetType()).isEqualTo(NotificationTargetType.CONVERSATION);
        assertThat(notice.targetPublicId()).isEqualTo(conversation.publicId());
        assertThat(received).containsExactly(NotificationEvent.created(notice.publicId(), 1));
    }

    @Test
    @DisplayName("같은 실행이 같은 호출을 다시 판정받아 앞선 승인 줄을 돌려받으면 알림이 늘지 않는다")
    void reusedPendingActionDoesNotNotifyAgain() {
        ConnectorPolicyAnswer first = ask(root, WRITE, ARGS);
        ConnectorPolicyAnswer second = ask(root, WRITE, ARGS);

        assertThat(second.actionId()).isEqualTo(first.actionId());
        assertThat(notifications.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("대화 없이 돈 실행의 승인 줄은 알림을 만들지 않는다")
    void approvalWithoutConversationDoesNotNotify() {
        String detached = startRun(null);

        ConnectorPolicyAnswer answer = ask(detached, WRITE, ARGS);

        assertThat(answer.actionId()).as("승인 줄은 만들어진다").isNotNull();
        assertThat(notifications.findAll()).isEmpty();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("대화를 지운 실행의 승인 줄은 알림을 만들지 않는다")
    void approvalInDeletedConversationDoesNotNotify() {
        deleteConversation();

        ConnectorPolicyAnswer answer = ask(root, WRITE, ARGS);

        assertThat(answer.actionId()).as("승인 줄은 만들어진다").isNotNull();
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("선언에 제목이 없는 도구의 알림 본문은 「이름 없는 동작」 이다")
    void untitledToolUsesUnnamedTitle() {
        ask(root, UNTITLED, ARGS);

        assertThat(onlyNotification().body()).isEqualTo("「" + ConnectorActionView.UNNAMED_TITLE + "」");
    }

    @Test
    @DisplayName("만료 정리가 줄을 만료하면 APPROVAL_EXPIRED 가 하나 생기고 본문은 승인 카드의 제목과 같다")
    void expiryCreatesExpiredNotificationWithCardTitle() {
        UUID actionId = ask(root, WRITE, ARGS).actionId();
        Instant expiresAt = onlyActionExpiry();
        notifications.deleteAll();
        received.clear();

        assertThat(service.expire(expiresAt.plusMillis(1))).isEqualTo(1);

        Notification notice = onlyNotification();
        ConnectorActionView card = service.listForConversation(me, conversation.id()).stream()
                .filter(view -> view.actionId().equals(actionId))
                .findFirst()
                .orElseThrow();
        assertThat(card.status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(notice.kind()).isEqualTo(NotificationKind.APPROVAL_EXPIRED);
        assertThat(notice.title()).isEqualTo("승인 요청이 만료됐어요");
        assertThat(notice.body()).isEqualTo("「" + card.title() + "」").isEqualTo("「메모 쓰기」");
        assertThat(notice.targetPublicId()).isEqualTo(conversation.publicId());
        assertThat(received).containsExactly(NotificationEvent.created(notice.publicId(), 1));
        assertThat(service.expire(expiresAt.plusMillis(2)))
                .as("이미 만료한 줄은 다시 알리지 않는다")
                .isZero();
        assertThat(notifications.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("카탈로그를 읽지 못한 채 만료하면 본문은 「이름 없는 동작」 이다")
    void expiryWithoutCatalogUsesUnnamedTitle() {
        ask(root, WRITE, ARGS);
        Instant expiresAt = onlyActionExpiry();
        notifications.deleteAll();
        when(connector.readCatalog()).thenThrow(new IllegalStateException("catalog down"));
        ConnectorPolicyTestDoubles.expireCatalog();

        assertThat(service.expire(expiresAt.plusMillis(1))).isEqualTo(1);

        assertThat(onlyNotification().body()).isEqualTo("「" + ConnectorActionView.UNNAMED_TITLE + "」");
    }

    @Test
    @DisplayName("대화 없이 돈 실행의 승인 줄만 만료하면 알림을 만들지 않고 카탈로그도 읽지 않는다")
    void expiryWithoutConversationSkipsNotificationAndCatalog() {
        ask(startRun(null), WRITE, ARGS);
        Instant expiresAt = onlyActionExpiry();
        ConnectorPolicyTestDoubles.expireCatalog();
        clearInvocations(connector);

        assertThat(service.expire(expiresAt.plusMillis(1))).isEqualTo(1);

        verify(connector, never()).readCatalog();
        assertThat(notifications.findAll()).isEmpty();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("대화를 지운 뒤 만료한 승인 줄은 알림을 만들지 않는다")
    void expiryInDeletedConversationDoesNotNotify() {
        ask(root, WRITE, ARGS);
        Instant expiresAt = onlyActionExpiry();
        notifications.deleteAll();
        deleteConversation();

        assertThat(service.expire(expiresAt.plusMillis(1))).isEqualTo(1);

        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("기다리는 시간이 지난 줄을 승인하려다 만료되면 APPROVAL_EXPIRED 를 남긴다")
    void approvingPastExpiryCreatesExpiredNotification() {
        UUID actionId = ask(root, WRITE, ARGS).actionId();
        jdbc.update("UPDATE connector_action SET expires_at = ?", Instant.parse("2020-01-01T00:00:00Z"));
        notifications.deleteAll();

        ConnectorActionView closed = service.approve(me, actionId, null);

        Notification notice = onlyNotification();
        assertThat(closed.status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(notice.kind()).isEqualTo(NotificationKind.APPROVAL_EXPIRED);
        assertThat(notice.body()).isEqualTo("「" + closed.title() + "」");
        assertThat(notice.targetPublicId()).isEqualTo(conversation.publicId());
    }

    /** 그 대화로 도는 실행 하나를 만들고 그 루트 session 을 돌려준다. 대화가 없으면 null 을 넘긴다. */
    private String startRun(Long conversationId) {
        String session = "fos-" + UUID.randomUUID();
        executions.save(AgentExecution.builder()
                .userId(owner.id())
                .agentId(agent.id())
                .conversationId(conversationId)
                .profileName(PROFILE)
                .hermesSessionId(session)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-10-01T00:00:00Z"))
                .build());
        return session;
    }

    /** 루트 session 에서 부른 새 호출 하나의 판정을 묻는다. */
    private ConnectorPolicyAnswer ask(String session, String tool, String args) {
        return policies.decide(
                PROFILE, session, session, "call_" + UUID.randomUUID(), "mcp__demo__" + tool, tool, args);
    }

    private void deleteConversation() {
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Instant.now(), conversation.id());
    }

    private Instant onlyActionExpiry() {
        assertThat(actions.findAll()).as("connector_action 의 줄").hasSize(1);
        return actions.findAll().getFirst().expiresAt();
    }

    private Notification onlyNotification() {
        List<Notification> rows = notifications.findAll();
        assertThat(rows).as("notification 의 줄").hasSize(1);
        return rows.getFirst();
    }
}
