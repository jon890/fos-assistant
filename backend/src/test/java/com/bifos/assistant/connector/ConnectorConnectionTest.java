package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorConnectionTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");
    private static final ConnectionFields FIELDS =
            new ConnectionFields(Map.of("scope", "a"), Map.of("token", "demo_ok_"));

    @Test
    @DisplayName("해제를 시작한 연결은 대기 상태여도 다시 켜려는 의도가 아니다")
    void disconnectingPendingConnectionDoesNotIntendToEnable() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", agent(), CREATED);
        connection.registered(FIELDS, false, CREATED);
        connection.beginDisconnect(LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.desiredEnabled()).isFalse();
        assertThat(connection.agent().enabled()).isFalse();
    }

    @Test
    @DisplayName("다시 등록이 성공하면 켜려는 의도가 돌아온다")
    void restoresEnableIntentAfterReRegistrationSucceeds() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", agent(), CREATED);
        connection.beginDisconnect(CREATED);
        connection.registered(FIELDS, true, LATER);

        assertThat(connection.desiredEnabled()).isTrue();
        assertThat(connection.restartRequired()).isTrue();
        assertThat(connection.fields()).isEqualTo(FIELDS);
    }

    @Test
    @DisplayName("상태 전이는 인자로 받은 시각을 적는다")
    void transitionsRecordTheGivenInstant() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", agent(), CREATED);
        assertThat(connection.createdAt()).isEqualTo(CREATED);
        assertThat(connection.checkedAt()).isNull();

        connection.ready(LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(connection.checkedAt()).isEqualTo(LATER);
        assertThat(connection.updatedAt()).isEqualTo(LATER);
        assertThat(connection.createdAt()).isEqualTo(CREATED);
        assertThat(connection.agent().enabled()).isTrue();
    }

    @Test
    @DisplayName("재시작 대기는 거짓이 와도 앞선 참을 지우지 않는다")
    void restartRequiredAccumulatesWithLogicalOr() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", agent(), CREATED);
        connection.markRestartRequired(true);
        connection.markRestartRequired(false);
        connection.registered(FIELDS, false, LATER);

        assertThat(connection.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("해제하면 칸 값과 비밀 앞부분을 비우고 반영 확인이 재시작 대기를 푼다")
    void disconnectClearsFieldsAndConfirmClearsRestart() {
        ConnectorConnection connection = ConnectorConnection.pending(1L, "demo-notes", agent(), CREATED);
        connection.registered(FIELDS, false, CREATED);

        connection.disconnected(true, LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.fields()).isEqualTo(ConnectionFields.empty());
        assertThat(connection.restartRequired()).isTrue();
        assertThat(connection.desiredEnabled()).isFalse();

        connection.confirmDisconnected(LATER);

        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.restartRequired()).isFalse();
    }

    private static Agent agent() {
        return Agent.of(
                "demo",
                "검사용 메모",
                "demo-profile",
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L);
    }
}
