package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.AgentPurgeWriter;
import com.bifos.assistant.agent.application.model.AgentPurgeOutcome;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import com.bifos.assistant.agent.domain.AgentToolsetRequest;
import com.bifos.assistant.agent.domain.type.AgentMemoryCollectionChangeType;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionChangeRepository;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentToolsetRequestRepository;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 지운 에이전트 하나를 한 트랜잭션으로 지우는지 확인한다. 정리 작업의 일정을 기다리지 않고 직접 부른다. */
@BackendIntegrationTest
class AgentPurgeWriterTest {

    /** 지운 시각을 아주 옛날로 두어 다른 검사가 만든 에이전트와 겹치지 않게 한다. */
    static final Instant DELETED_AT = Instant.parse("2000-01-01T00:00:00Z");

    static final Instant CUTOFF = Instant.parse("2000-01-08T00:00:00Z");

    static final Instant NOW = Instant.parse("2026-10-09T05:00:00Z");

    @Autowired
    AgentPurgeWriter writer;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentMemoryCollectionRepository collections;

    @Autowired
    AgentMemoryCollectionChangeRepository collectionChanges;

    @Autowired
    AgentToolsetRequestRepository toolsetRequests;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ProactiveLoopSettingRepository loopSettings;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    AppUser owner;
    Agent agent;

    @BeforeEach
    void setUp() {
        String email = "agent-purge-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        String code = "purge-" + UUID.randomUUID().toString().substring(0, 8);
        agent = agents.save(Agent.of(
                code,
                "정리할 에이전트",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    @Test
    @DisplayName("딸린 줄이 모두 있는 지운 에이전트를 지우고 대화와 실행은 남긴다")
    void purgesAgentWithAttachedRowsAndKeepsConversationAndExecution() {
        Attached attached = attachEverything();
        markAgentDeleted(DELETED_AT);

        AgentPurgeOutcome outcome = writer.purge(agent.id(), CUTOFF);

        assertThat(outcome).isEqualTo(AgentPurgeOutcome.PURGED);
        assertThat(agents.findById(agent.id())).isEmpty();
        assertThat(countOf("agent_memory_collection", "agent_id")).isZero();
        assertThat(countOf("agent_memory_collection_change", "agent_id")).isZero();
        assertThat(countOf("agent_toolset_request", "agent_id")).isZero();
        assertThat(countOf("agent_connector_binding", "agent_id")).isZero();
        assertThat(countOf("proactive_loop_setting", "agent_id")).isZero();
        assertThat(countOf("proactive_check", "agent_id")).isZero();
        assertThat(actions.findById(attached.actionId()))
                .as("승인 이력은 남고 에이전트만 비운다")
                .get()
                .extracting(ConnectorAction::agentId)
                .isNull();
        assertThat(connections.findById(attached.connectionId()))
                .as("연결은 사용자의 것이라 남는다")
                .isPresent();
        assertThat(connectionAgentId(attached.connectionId()))
                .as("연결의 옛 에이전트 칸은 비운다")
                .isNull();
        assertThat(conversations.findById(attached.conversationId())).isPresent();
        assertThat(executions.findById(attached.executionId())).isPresent();
    }

    @Test
    @DisplayName("cutoff 보다 늦게 지운 에이전트는 남긴다")
    void keepsAgentDeletedAfterCutoff() {
        attachEverything();
        markAgentDeleted(CUTOFF.plusSeconds(1));

        AgentPurgeOutcome outcome = writer.purge(agent.id(), CUTOFF);

        assertThat(outcome).isEqualTo(AgentPurgeOutcome.GONE);
        assertThat(agents.findById(agent.id())).isPresent();
        assertAttachedRowsRemain();
    }

    @Test
    @DisplayName("지우지 않은 에이전트는 건드리지 않는다")
    void leavesActiveAgentUntouched() {
        attachEverything();

        AgentPurgeOutcome outcome = writer.purge(agent.id(), CUTOFF);

        assertThat(outcome).isEqualTo(AgentPurgeOutcome.GONE);
        assertThat(agents.findById(agent.id()))
                .get()
                .extracting(Agent::deletedAt)
                .isNull();
        assertAttachedRowsRemain();
    }

    @Test
    @DisplayName("지웠지만 정리되지 않은 대화가 있으면 미루고, 대화가 정리되면 지운다")
    void waitsForUnpurgedConversationThenPurges() {
        Attached attached = attachEverything();
        markAgentDeleted(DELETED_AT);
        conversationWriter.deleteIfActive(attached.conversationId(), owner.id(), NOW);

        AgentPurgeOutcome waiting = writer.purge(agent.id(), CUTOFF);

        assertThat(waiting).isEqualTo(AgentPurgeOutcome.WAITING);
        assertThat(agents.findById(agent.id())).isPresent();
        assertAttachedRowsRemain();
        assertThat(actions.findById(attached.actionId()))
                .get()
                .extracting(ConnectorAction::agentId)
                .isEqualTo(agent.id());
        assertThat(connectionAgentId(attached.connectionId())).isEqualTo(agent.id());

        transactions.executeWithoutResult(status -> conversations.markPurged(attached.conversationId(), NOW));
        AgentPurgeOutcome purged = writer.purge(agent.id(), CUTOFF);

        assertThat(purged).isEqualTo(AgentPurgeOutcome.PURGED);
        assertThat(agents.findById(agent.id())).isEmpty();
    }

    /** 에이전트를 가리키는 표마다 한 줄씩 만든다. 지우지 않은 대화 하나와 그 대화의 실행 하나도 만든다. */
    Attached attachEverything() {
        collections.saveAndFlush(AgentMemoryCollection.of(agent.id(), "core", false, NOW));
        collectionChanges.saveAndFlush(AgentMemoryCollectionChange.of(
                agent.id(), "core", AgentMemoryCollectionChangeType.GRANTED, false, owner.id(), NOW));
        toolsetRequests.saveAndFlush(AgentToolsetRequest.of(1L, agent.id(), owner.id(), "image_gen", NOW));

        ConnectorConnection connection =
                connections.saveAndFlush(ConnectorConnection.pending(owner.id(), "demo-mail", NOW));
        jdbc.update("UPDATE connector_connection SET agent_id = ? WHERE id = ?", agent.id(), connection.id());
        bindings.saveAndFlush(ConnectorBinding.pending(agent, connection, "demo-mail", NOW));

        Conversation conversation =
                conversations.saveAndFlush(Conversation.startedBy(owner.id(), "남는 대화", agent.id(), NOW));
        AgentExecution execution = executions.saveAndFlush(AgentExecution.builder()
                .userId(owner.id())
                .agentId(agent.id())
                .conversationId(conversation.id())
                .profileName(agent.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(NOW)
                .build());
        ConnectorAction action = actions.saveAndFlush(ConnectorAction.decided(
                connection,
                agent.id(),
                execution,
                "mcp__mail__read",
                "read",
                new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE),
                true,
                "agent-purge-" + UUID.randomUUID(),
                "synthetic-hash",
                NOW));

        loopSettings.saveAndFlush(ProactiveLoopSetting.of(owner.id(), agent.id(), true, null, NOW));
        ProactiveCheck check = checks.saveAndFlush(
                ProactiveCheck.started(owner.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, NOW));

        return new Attached(connection.id(), action.id(), conversation.id(), execution.id(), check.id());
    }

    /** 엔티티로 적어 읽는 쪽과 같은 UTC 로 저장한다. */
    void markAgentDeleted(Instant deletedAt) {
        Agent loaded = agents.findById(agent.id()).orElseThrow();
        loaded.markDeleted(deletedAt);
        agents.saveAndFlush(loaded);
    }

    void assertAttachedRowsRemain() {
        assertThat(countOf("agent_memory_collection", "agent_id")).isEqualTo(1);
        assertThat(countOf("agent_memory_collection_change", "agent_id")).isEqualTo(1);
        assertThat(countOf("agent_toolset_request", "agent_id")).isEqualTo(1);
        assertThat(countOf("agent_connector_binding", "agent_id")).isEqualTo(1);
        assertThat(countOf("proactive_loop_setting", "agent_id")).isEqualTo(1);
        assertThat(countOf("proactive_check", "agent_id")).isEqualTo(1);
        assertThat(countOf("connector_action", "agent_id")).isEqualTo(1);
    }

    /** 그 표에서 이 검사의 에이전트를 가리키는 줄 수다. 표와 칸 이름은 이 클래스가 정한 값만 넘긴다. */
    long countOf(String table, String column) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, agent.id());
        return count == null ? 0 : count;
    }

    Long connectionAgentId(Long connectionId) {
        return jdbc.queryForObject("SELECT agent_id FROM connector_connection WHERE id = ?", Long.class, connectionId);
    }

    record Attached(Long connectionId, Long actionId, Long conversationId, Long executionId, Long checkId) {}
}
