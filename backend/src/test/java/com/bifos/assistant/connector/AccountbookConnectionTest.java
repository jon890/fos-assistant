package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.connector.domain.AccountbookConnection;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountbookConnectionTest {
    @Test
    void 해제를_시작한_연결은_대기_상태여도_다시_켜려는_의도가_아니다() {
        AccountbookConnection connection = AccountbookConnection.pending(1L, agent());
        connection.registered("fab_1234", UUID.randomUUID(), false);
        connection.beginDisconnect();

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.isDesiredEnabled()).isFalse();
        assertThat(connection.getAgent().enabled()).isFalse();
    }

    @Test
    void 다시_등록이_성공하면_켜려는_의도가_돌아온다() {
        AccountbookConnection connection = AccountbookConnection.pending(1L, agent());
        connection.beginDisconnect();
        connection.registered("fab_1234", null, true);

        assertThat(connection.isDesiredEnabled()).isTrue();
        assertThat(connection.isRestartRequired()).isTrue();
    }

    private static Agent agent() {
        return Agent.of("accountbook", "가계부", "accountbook-profile", "http://localhost",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, 1L);
    }
}
