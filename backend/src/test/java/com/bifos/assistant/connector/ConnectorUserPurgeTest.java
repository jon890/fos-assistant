package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionPurge;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 정리가 연결을 지울 때 바인딩 떼기와 보관 파일 삭제를 다시 해도 같은 결과로 끝나는지 확인한다(ADR-20261101 / user-purge).
 *
 * <p>Hermes 커넥터 클라이언트는 mock 이다. 떼기와 보관 파일 삭제가 이미 끝난 것을 다시 받아도 성공한다는 계약을 대역으로 둔다.
 */
@BackendIntegrationTest
class ConnectorUserPurgeTest {

    private static final String CONNECTOR = "demo";

    @Autowired
    ConnectorConnectionPurge service;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    TransactionTemplate transactions;

    AppUser owner;
    ConnectorConnection connection;
    ConnectorBinding binding;


    @BeforeEach
    void setUp() {
        String email = "connector-purge-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        String code = "cpurge-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = agents.saveAndFlush(Agent.of(code, "연결을 붙인 에이전트", code, "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id()));
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        connection = connections.saveAndFlush(ConnectorConnection.pending(owner.id(), CONNECTOR, now));
        binding = bindings.saveAndFlush(ConnectorBinding.pending(agent, connection, CONNECTOR, now));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.deleteVault(anyString())).thenReturn(true);
    }


    @Test
    @DisplayName("보관 파일 삭제가 실패하면 행이 모두 남고, 다시 하면 바인딩을 다시 떼고 보관 파일과 행을 지운 뒤 더 부르지 않는다")
    void retriesDetachAndVaultDeletionToTheSameResult() {
        when(connector.deleteVault(connection.vault())).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> purge()).isInstanceOf(ApiException.class);

        assertThat(bindings.findById(binding.id())).as("되돌려진 트랜잭션의 바인딩 행은 남는다").isPresent();
        assertThat(connections.findById(connection.id())).isPresent();
        verify(connector, times(1)).unbindConnector(anyString(), anyString());

        reset(connector);
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.deleteVault(anyString())).thenReturn(false);
        assertThat(purge()).isEqualTo(1);

        assertThat(bindings.findById(binding.id())).isEmpty();
        assertThat(connections.findById(connection.id())).isEmpty();
        verify(connector).unbindConnector(anyString(), anyString());
        verify(connector).deleteVault(connection.vault());

        reset(connector);
        assertThat(purge()).as("지울 것이 없으면 Hermes 를 부르지 않는다").isZero();
        verify(connector, never()).unbindConnector(anyString(), anyString());
        verify(connector, never()).deleteVault(anyString());
    }


    private int purge() {
        return transactions.execute(status -> service.purgeUser(owner.id()));
    }
}
