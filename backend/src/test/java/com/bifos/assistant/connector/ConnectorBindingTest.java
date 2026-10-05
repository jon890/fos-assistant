package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorBindingTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-01-03T00:00:00Z");

    @Test
    @DisplayName("옛 커넥터 에이전트의 바인딩은 ready 가 에이전트를 켜고 pending 이 끄며 사진 받기를 내린다")
    void legacyConnectorAgentFollowsBindingState() {
        Agent agent = agent(true);
        agent.changeAccess(false, agent.visibility(), agent.ownerUserId());
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection(agent), "demo", CREATED);

        binding.ready(LATER);

        assertThat(binding.status()).isEqualTo(BindingStatus.READY);
        assertThat(binding.checkedAt()).isEqualTo(LATER);
        assertThat(agent.enabled()).as("ready 뒤 옛 에이전트").isTrue();

        agent.acceptConnectorAttachments(true);
        binding.pending(LATEST);

        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(binding.updatedAt()).isEqualTo(LATEST);
        assertThat(agent.enabled()).as("pending 뒤 옛 에이전트").isFalse();
        assertThat(agent.connectorAttachments()).as("pending 뒤 사진 받기").isFalse();
    }

    @Test
    @DisplayName("일반 에이전트의 바인딩은 상태가 바뀌어도 에이전트의 enabled 를 바꾸지 않는다")
    void ordinaryAgentEnabledIsUntouched() {
        Agent enabledAgent = agent(false);
        ConnectorBinding binding = ConnectorBinding.pending(enabledAgent, connection(null), "demo", CREATED);

        binding.beginInstall(LATER);
        binding.installed(true, LATER);
        binding.pending(LATER);

        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(enabledAgent.enabled()).as("PENDING 바인딩의 켜진 일반 에이전트").isTrue();

        Agent disabledAgent = agent(false);
        disabledAgent.changeAccess(false, disabledAgent.visibility(), disabledAgent.ownerUserId());
        ConnectorBinding other = ConnectorBinding.pending(disabledAgent, connection(null), "demo", CREATED);

        other.ready(LATER);

        assertThat(other.status()).isEqualTo(BindingStatus.READY);
        assertThat(disabledAgent.enabled()).as("READY 바인딩의 꺼진 일반 에이전트").isFalse();
    }

    @Test
    @DisplayName("installed 는 재시작 대기를 누적하고 재시작이 필요한 설치의 시각만 대기 시작으로 적는다")
    void installedAccumulatesRestartRequired() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(false), connection(null), "demo", CREATED);
        assertThat(binding.restartRequired()).isFalse();
        assertThat(binding.restartRequiredSince()).isNull();
        assertThat(binding.desiredEnabled()).isFalse();

        binding.beginInstall(LATER);
        binding.installed(true, LATER);

        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(binding.desiredEnabled()).isTrue();
        assertThat(binding.restartRequired()).isTrue();
        assertThat(binding.restartRequiredSince()).isEqualTo(LATER);

        binding.beginInstall(LATEST);
        assertThat(binding.desiredEnabled()).as("설치를 시작하면 켜려는 의도를 내린다").isFalse();
        binding.installed(false, LATEST);

        assertThat(binding.restartRequired())
                .as("재시작이 필요 없는 설치가 앞선 대기를 지우지 않는다")
                .isTrue();
        assertThat(binding.restartRequiredSince())
                .as("재시작이 필요 없는 설치는 대기 시작 시각을 옮기지 않는다")
                .isEqualTo(LATER);

        binding.ready(LATEST);

        assertThat(binding.restartRequired()).as("반영되면 대기가 풀린다").isFalse();
    }

    @Test
    @DisplayName("재시작 대기 표시는 거짓이 와도 앞선 참을 지우지 않는다")
    void markRestartRequiredAccumulatesWithLogicalOr() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(false), connection(null), "demo", CREATED);

        binding.markRestartRequired(true);
        binding.markRestartRequired(false);

        assertThat(binding.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("서버 이름이 빈 옛 바인딩에 서버 이름을 적는다")
    void recordServerFillsServerName() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(false), connection(null), null, CREATED);
        assertThat(binding.mcpServer()).isNull();

        binding.recordServer("demo");

        assertThat(binding.mcpServer()).isEqualTo("demo");
    }

    private static Agent agent(boolean connectorManaged) {
        Agent agent = Agent.of(
                "demo",
                "검사용 메모",
                "demo-profile",
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L,
                CREATED);
        if (connectorManaged) {
            agent.markConnectorManaged();
        }
        return agent;
    }

    private static ConnectorConnection connection(Agent agent) {
        return ConnectorConnection.pending(1L, "demo-notes", agent, CREATED);
    }
}
