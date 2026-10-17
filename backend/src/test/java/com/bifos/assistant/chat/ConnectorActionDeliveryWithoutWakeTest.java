package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionChanged;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 깨우기를 끈 채로도 승인 줄의 사건과 거절 알림 줄은 나가고 자동 turn 만 열리지 않는지 본다(ADR-050).
 *
 * <p>test profile 은 {@code assistant.delegation-wake.enabled} 가 꺼져 있다.
 */
@BackendIntegrationTest
class ConnectorActionDeliveryWithoutWakeTest {

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    ConversationEventHub hub;

    @Autowired
    ApplicationEventPublisher publisher;

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
    HermesRunsClient hermes;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private Agent chief;
    private Conversation conversation;
    private AgentExecution root;
    private final List<ChatEvent> seen = new CopyOnWriteArrayList<>();
    private Runnable unsubscribe = () -> {
    };


    @BeforeEach
    void setUp() {
        ((StubHermesRunsClient)hermes).reset();
        jdbc.update("DELETE FROM connector_action");
        when(connector.readCatalog()).thenThrow(new IllegalStateException());
        AppUser user = users.save(
            AppUser.of("no-wake-" + UUID.randomUUID() + "@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "no-wake-" + UUID.randomUUID();
        chief = agents.save(Agent.of(
            code,
            "비서",
            code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            user.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        root = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(chief.id())
            .profileName(code)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now())
            .build());
        seen.clear();
        unsubscribe = hub.subscribe(conversation.id(), seen::add);
    }


    @AfterEach
    void tearDown() {
        unsubscribe.run();
        jdbc.update("DELETE FROM connector_action");
    }


    @Test
    @DisplayName("깨우기가 꺼져 있어도 approval 사건과 거절 알림 줄은 나가고 자동 turn 은 열리지 않는다")
    void approvalEventAndClosureNoticeSurviveDisabledWake() {
        UUID rejected = action("PENDING", null);
        UUID succeeded = action("SUCCEEDED", "{\"saved\":true}");

        actionService.reject(dad, rejected);
        publisher.publishEvent(new ConnectorActionChanged(conversation.id(), succeeded));

        assertThat(seen).extracting(ChatEvent::type).containsExactly("approval", "system", "approval");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
            .extracting(ChatMessage::content)
            .containsExactly("「이름 없는 동작」 요청을 거절했어요");
        assertThat(((StubHermesRunsClient)hermes).received()).isEmpty();
    }


    private UUID action(String status, String resultText) {
        return ConnectorActionDeliveryTest.insertAction(
            jdbc, dad.id(), chief.id(), root.id(), conversation.id(), status, resultText, null);
    }
}
