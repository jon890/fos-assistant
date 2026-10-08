package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorCallHistory;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@BackendIntegrationTest
@Transactional
class ConnectorCallHistoryTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Autowired
    private ConnectorCallHistory history;

    @Autowired
    private ConnectorActionRepository actions;

    @Autowired
    private ConnectorConnectionRepository connections;

    @Autowired
    private AgentExecutionRepository executions;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private AppUserRepository users;

    @Test
    @DisplayName("자식의 커넥터 호출을 루트에서 찾되 호출 전과 다른 트리에는 적용하지 않는다")
    void findsChildCallWithTimestampAndTreeBoundary() {
        AppUser user = users.save(AppUser.of("history@example.com", "history", 1L, UserRole.MEMBER, NOW));
        Agent agent = agents.save(Agent.of(
                "history",
                "history",
                "history",
                "http://agent-runtime.test",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                NOW));
        AgentExecution root = execution(user, agent, null, null);
        AgentExecution child = execution(user, agent, root.id(), root.id());
        AgentExecution otherRoot = execution(user, agent, null, null);
        ConnectorConnection connection = connections.save(ConnectorConnection.pending(user.id(), "demo-mail", NOW));

        assertThat(history.calledBefore(root.id(), NOW.plusSeconds(1))).isFalse();
        actions.saveAndFlush(ConnectorAction.decided(
                connection,
                agent.id(),
                child,
                "mcp__mail__read",
                "read",
                new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE),
                true,
                "history-child",
                "synthetic-hash",
                NOW));

        assertThat(history.calledBefore(root.id(), NOW.minusMillis(1))).isFalse();
        assertThat(history.calledBefore(root.id(), NOW)).isTrue();
        assertThat(history.calledBefore(otherRoot.id(), NOW.plusSeconds(1))).isFalse();
        // 부모가 먼저 읽고 자식에게 위임하는 경우도 같은 루트로 찾는다.
        actions.saveAndFlush(ConnectorAction.decided(
                connection,
                agent.id(),
                otherRoot,
                "mcp__mail__read",
                "read",
                new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE),
                true,
                "history-root",
                "synthetic-hash",
                NOW.plusSeconds(2)));
        assertThat(history.calledBefore(otherRoot.id(), NOW.plusSeconds(1))).isFalse();
        assertThat(history.calledBefore(otherRoot.id(), NOW.plusSeconds(2))).isTrue();
    }

    @Test
    @DisplayName("호출 이력을 읽지 못하면 원문을 남기지 않게 호출 뒤로 판정한다")
    void hidesOnHistoryFailure() {
        ConnectorActionRepository failed = mock(ConnectorActionRepository.class);
        when(failed.existsCallInTreeBefore(1L, NOW)).thenThrow(new IllegalStateException("unavailable"));
        assertThat(new ConnectorCallHistory(failed).calledBefore(1L, NOW)).isTrue();
    }

    private AgentExecution execution(AppUser user, Agent agent, Long parent, Long root) {
        return executions.save(AgentExecution.builder()
                .userId(user.id())
                .agentId(agent.id())
                .parentExecutionId(parent)
                .rootExecutionId(root)
                .profileName(agent.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(NOW)
                .build());
    }
}
