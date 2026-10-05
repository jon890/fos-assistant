package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectorConnectionTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");
    private static final ConnectionFields FIELDS =
            new ConnectionFields(Map.of("scope", "a"), Map.of("token", "demo_ok_"));

    @Test
    @DisplayName("새 연결은 에이전트 없이 PENDING 으로 만들고 보관 파일 이름은 연결 번호 앞에 c 를 붙인다")
    void newConnectionHasNoAgentAndNamesVaultAfterId() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", CREATED);
        ReflectionTestUtils.setField(connection, "id", 42L);

        assertThat(connection.agent()).isNull();
        assertThat(connection.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.vaultStored()).isFalse();
        assertThat(connection.vault()).isEqualTo("c42");
    }

    @Test
    @DisplayName("값을 보관 파일에 쓴 연결은 READY 이고 칸 값을 적으며 세어 둔 선언 밖 도구 수를 비운다")
    void connectedStoresFieldsAndBecomesReady() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", CREATED);
        connection.recordUndeclaredTools(2);

        connection.connected(FIELDS, LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(connection.vaultStored()).isTrue();
        assertThat(connection.fields()).isEqualTo(FIELDS);
        assertThat(connection.undeclaredTools()).isZero();
        assertThat(connection.checkedAt()).isEqualTo(LATER);
        assertThat(connection.updatedAt()).isEqualTo(LATER);
        assertThat(connection.createdAt()).isEqualTo(CREATED);
    }

    @Test
    @DisplayName("해제하면 칸 값과 비밀 앞부분과 보관 파일 표시를 비운다")
    void disconnectClearsFieldsAndVault() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", CREATED);
        connection.connected(FIELDS, CREATED);

        connection.disconnected(LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.fields()).isEqualTo(ConnectionFields.empty());
        assertThat(connection.vaultStored()).isFalse();
        assertThat(connection.checkedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("연결의 메서드는 옛 커넥터 에이전트를 켜거나 끄지 않고 사진 받기도 바꾸지 않는다")
    void connectionMethodsLeaveAgentUntouched() {
        Agent enabled = agent();
        enabled.acceptConnectorAttachments(true);
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", enabled, CREATED);

        connection.pending(LATER);
        connection.disconnected(LATER);

        assertThat(enabled.enabled()).as("PENDING 과 해제 뒤 켜진 에이전트").isTrue();
        assertThat(enabled.acceptsAttachments()).as("PENDING 과 해제 뒤 사진 받기").isTrue();

        Agent disabled = agent();
        disabled.changeAccess(false, disabled.visibility(), disabled.ownerUserId());
        ConnectorConnection other = ConnectorConnection.pending(1L, "demo-notes", disabled, CREATED);

        other.ready(LATER);

        assertThat(other.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(disabled.enabled()).as("READY 뒤 꺼진 에이전트").isFalse();
    }

    @Test
    @DisplayName("상태 전이는 인자로 받은 시각을 적는다")
    void transitionsRecordTheGivenInstant() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", CREATED);
        assertThat(connection.createdAt()).isEqualTo(CREATED);
        assertThat(connection.checkedAt()).isNull();

        connection.ready(LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(connection.checkedAt()).isEqualTo(LATER);
        assertThat(connection.updatedAt()).isEqualTo(LATER);
        assertThat(connection.createdAt()).isEqualTo(CREATED);
    }

    private static Agent agent() {
        Agent agent = Agent.of(
                "demo",
                "검사용 메모",
                "demo-profile",
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L,
                Instant.now());
        agent.markConnectorManaged();
        return agent;
    }
}
