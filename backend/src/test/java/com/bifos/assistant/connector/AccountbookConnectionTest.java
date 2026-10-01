package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.connector.domain.AccountbookConnection;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountbookConnectionTest {
    @Test
    @DisplayName("해제를 시작한 연결은 대기 상태여도 다시 켜려는 의도가 아니다")
    void disconnectingPendingConnectionDoesNotIntendToEnable() {
        AccountbookConnection connection = AccountbookConnection.pending(1L, agent());
        connection.registered("fab_1234", UUID.randomUUID(), false);
        connection.beginDisconnect();

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.isDesiredEnabled()).isFalse();
        assertThat(connection.getAgent().enabled()).isFalse();
    }

    @Test
    @DisplayName("다시 등록이 성공하면 켜려는 의도가 돌아온다")
    void restoresEnableIntentAfterReRegistrationSucceeds() {
        AccountbookConnection connection = AccountbookConnection.pending(1L, agent());
        connection.beginDisconnect();
        connection.registered("fab_1234", null, true);

        assertThat(connection.isDesiredEnabled()).isTrue();
        assertThat(connection.isRestartRequired()).isTrue();
    }

    private static Agent agent() {
        return Agent.of(
                "accountbook",
                "가계부",
                "accountbook-profile",
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L);
    }
}
