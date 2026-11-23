package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.delivery.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.delivery.application.dto.DeliveryAttemptRef;
import com.bifos.assistant.chat.delivery.application.dto.DeliveryItemRef;
import com.bifos.assistant.chat.delivery.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 위임 기능의 끝남 조건과 전달 판정이 결과가 전해지는 두 길을 모두 알아보는지 본다.
 *
 * <p>상한 안에 끝난 맡김은 {@code agent_delegate} 의 도구 응답이 결과를 전하고 전했다고만 적는다. 묶음도 자동 답도 없다
 * (ADR-20261121 / delegate-wait). 이 길을 알아보지 못하면 배포 뒤 확인과 평가가 기능 마감까지 기다려 시간 초과로 끝난다.
 * 상한을 넘긴 맡김은 지금처럼 자동 답의 묶음이 전한다(ADR-040).
 */
@BackendIntegrationTest
class DeployCheckObserverDelegationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z");

    @Autowired
    DeployCheckObserver observer;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    @Autowired
    ResultDeliveryRecorder deliveries;

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

    private CurrentUser owner;
    private Agent assistant;
    private Conversation conversation;
    private AgentExecution root;


    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = users.save(AppUser.of("observer-" + suffix + "@example.com", "평가", 1L, UserRole.MEMBER));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        assistant = agents.save(Agent.of(
            "observer-" + suffix,
            "비서",
            "observer-" + suffix,
            "http://agent-runtime.test/p/observer-" + suffix,
            CostMode.API,
            CredentialScope.DEDICATED,
            AgentVisibility.PRIVATE,
            user.id()));
        conversation = conversations.save(Conversation.startedBy(user.id(), "맡기기 확인", assistant.id(), NOW));
        root = executions.save(AgentExecution.builder()
            .userId(user.id())
            .conversationId(conversation.id())
            .agentId(assistant.id())
            .profileName(assistant.hermesProfile())
            .costMode(CostMode.API)
            .status(ExecutionStatus.SUCCEEDED)
            .timing(NOW, NOW.plusSeconds(11))
            .build());
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM result_delivery WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM agent_execution WHERE root_execution_id = ?", root.id());
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", root.id());
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM conversation WHERE id = ?", conversation.id());
        jdbc.update("DELETE FROM agent WHERE id = ?", assistant.id());
        jdbc.update("DELETE FROM app_user WHERE id = ?", owner.id());
    }


    @Test
    @DisplayName("맡긴 답의 도구 응답이 결과를 전한 맡김은 묶음이 없어도 끝났고 전해진 것으로 본다")
    void settlesAndDeliversWhenToolResponseCarriedTheResult() {
        AgentExecution child = child(ExecutionStatus.SUCCEEDED);
        deliveryWriter.markResultDelivered(child.id(), NOW.plusSeconds(9));

        assertThat(observer.delegationSettled(conversation.id())).isTrue();
        assertThat(observer.observe(owner, conversation.id(), false).delivered()).isTrue();
    }


    @Test
    @DisplayName("도는 자식이나 아직 전하지 않은 끝난 결과가 있으면 끝나지 않았다")
    void waitsForRunningChildAndUndeliveredResult() {
        AgentExecution child = child(ExecutionStatus.RUNNING);

        assertThat(observer.delegationSettled(conversation.id())).isFalse();

        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", child.id());

        assertThat(observer.delegationSettled(conversation.id())).as("자동 답이 아직 전하지 않았다").isFalse();
        assertThat(observer.observe(owner, conversation.id(), false).delivered()).isFalse();
    }


    @Test
    @DisplayName("자동 답의 묶음이 도는 동안은 끝나지 않았고, 그 묶음이 전하면 끝났고 전해진 것으로 본다")
    void settlesAfterAutoTurnDeliveryFinishes() {
        AgentExecution child = child(ExecutionStatus.SUCCEEDED);
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "맡긴 일이 끝났다", NOW.plusSeconds(20)));
        deliveryWriter.markResultDelivered(child.id(), NOW.plusSeconds(20));
        DeliveryAttemptRef attempt = deliveries.open(
            conversation.id(),
            List.of(new DeliveryItemRef(ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(child.id()))),
            notice.id(),
            notice.id(),
            NOW.plusSeconds(20));

        assertThat(observer.delegationSettled(conversation.id())).isFalse();

        deliveries.finish(attempt, DeliveryAttemptStatus.SUCCEEDED, null);

        assertThat(observer.delegationSettled(conversation.id())).isTrue();
        assertThat(observer.observe(owner, conversation.id(), false).delivered()).isTrue();
    }


    @Test
    @DisplayName("자동 답의 묶음이 실패한 결과는 전했다고 적혀도 전해진 것으로 보지 않는다")
    void failedAutoTurnDeliveryIsNotDelivered() {
        AgentExecution child = child(ExecutionStatus.SUCCEEDED);
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "맡긴 일이 끝났다", NOW.plusSeconds(20)));
        deliveryWriter.markResultDelivered(child.id(), NOW.plusSeconds(20));
        DeliveryAttemptRef attempt = deliveries.open(
            conversation.id(),
            List.of(new DeliveryItemRef(ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(child.id()))),
            notice.id(),
            notice.id(),
            NOW.plusSeconds(20));
        deliveries.finish(attempt, DeliveryAttemptStatus.FAILED, "HERMES_RUN_FAILED");

        assertThat(observer.delegationSettled(conversation.id())).isTrue();
        assertThat(observer.observe(owner, conversation.id(), false).delivered()).isFalse();
    }


    private AgentExecution child(ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
            .userId(owner.id())
            .conversationId(conversation.id())
            .agentId(assistant.id())
            .parentExecutionId(root.id())
            .rootExecutionId(root.id())
            .delegationKey("observer-" + UUID.randomUUID())
            .profileName("observer-child")
            .costMode(CostMode.API)
            .status(status)
            .startedAt(NOW.plusSeconds(3))
            .build());
    }
}
