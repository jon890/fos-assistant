package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.dto.ConnectionOAuthRevoke;
import com.bifos.assistant.connector.connection.application.dto.ConnectionOAuthRevoke.Status;
import com.bifos.assistant.connector.connection.application.dto.ConnectionSnapshot;
import com.bifos.assistant.connector.connection.application.dto.ConnectorOperationFailure;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthTokens;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;

/** 단추 연결을 해제할 때 서비스 쪽 허락을 취소하는지 대시보드 대역으로 확인한다(스펙 REQ8). */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorOAuthRevokeTest {
    private static final String PLUGIN = "demo-notes";
    private static final String SHARED = "demo-mail";
    private static final String OTHER_SHARED = "demo-calendar";
    private static final String SETTINGS = "https://accounts.example.test/settings";
    private static final String INSTALL = "https://accounts.example.test/installations";
    private static final String ACCESS_TOKEN = "access-token-for-test-1";

    @Autowired
    ConnectorConnectionService service;

    @Autowired
    ConnectorOAuthTokens tokens;

    @Autowired
    ConnectorOAuthApps apps;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesConnectorClient hermes;

    @Autowired
    HermesConnectorOAuthClient oauth;

    @Autowired
    Clock clock;

    private AppUser owner;


    @BeforeEach
    void setUp() {
        when(hermes.readCatalog()).thenReturn(List.of(
            manifest(PLUGIN, new ConnectorOAuthDeclaration("github", AppScope.PLUGIN, List.of("token"), SETTINGS, INSTALL)),
            manifest(SHARED, new ConnectorOAuthDeclaration("google", AppScope.SHARED, List.of("token"), SETTINGS, null)),
            manifest(OTHER_SHARED, new ConnectorOAuthDeclaration("google", AppScope.SHARED, List.of("token"), SETTINGS, null))));
        long group = 900_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 90_000L);
        owner = user(UserRole.MEMBER, group);
        AppUser admin = user(UserRole.ADMIN, group);
        apps.save(PLUGIN, "example-client", "client-secret-for-test-123", "demo-app", admin.id());
        apps.save(SHARED, "shared-client", "shared-secret-for-test-123", null, admin.id());
    }


    @Test
    @DisplayName("해제는 보관 파일을 지우기 전에 메모리의 토큰과 함께 허락을 취소하고 앱 설치 화면 주소를 답한다")
    void revokesBeforeDeletingTheVault() {
        ConnectorConnection connection = connection(PLUGIN, new ConnectionAuth("github", false));
        when(oauth.refresh(eq(connection.vault()), eq(PLUGIN), any()))
            .thenReturn(new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plus(Duration.ofHours(8))));
        tokens.refresh(connection.id(), null);
        when(oauth.revoke(eq(connection.vault()), eq(PLUGIN), any(), any())).thenReturn(Outcome.OK);

        ConnectionSnapshot disconnected = service.disconnect(principal(), PLUGIN);

        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.REVOKED, null, INSTALL));
        InOrder order = inOrder(oauth, hermes);
        order.verify(oauth).revoke(eq(connection.vault()), eq(PLUGIN), any(), eq(ACCESS_TOKEN));
        order.verify(hermes).deleteVault(connection.vault());
        assertThat(tokens.current(connection.id())).isEmpty();
    }


    @Test
    @DisplayName("서비스가 답하지 않거나 대시보드 호출이 실패해도 해제는 끝나고 직접 거둘 설정 화면 주소를 답한다")
    void failedRevokeStillDisconnects() {
        ConnectorConnection connection = connection(PLUGIN, new ConnectionAuth("github", false));
        when(oauth.revoke(eq(connection.vault()), eq(PLUGIN), any(), any())).thenReturn(Outcome.UNAVAILABLE);

        ConnectionSnapshot disconnected = service.disconnect(principal(), PLUGIN);

        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.FAILED, SETTINGS, INSTALL));
        verify(oauth).revoke(eq(connection.vault()), eq(PLUGIN), any(), eq(null));
        verify(hermes).deleteVault(connection.vault());

        ConnectorConnection again = connection(SHARED, new ConnectionAuth("google", false));
        when(oauth.revoke(eq(again.vault()), eq(SHARED), any(), any())).thenThrow(new IllegalStateException("boom"));

        ConnectionSnapshot second = service.disconnect(principal(), SHARED);

        assertThat(second.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(second.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.FAILED, SETTINGS, null));
        verify(hermes).deleteVault(again.vault());
    }


    @Test
    @DisplayName("공유 앱은 같은 사용자의 다른 쓸 수 있는 단추 연결이 있으면 취소하지 않고 없으면 취소한다")
    void sharedAppIsRevokedOnlyForTheLastConnection() {
        ConnectorConnection mail = connection(SHARED, new ConnectionAuth("google", false));
        ConnectorConnection calendar = connection(OTHER_SHARED, new ConnectionAuth("google", false));
        when(oauth.revoke(any(), any(), any(), any())).thenReturn(Outcome.OK);

        ConnectionSnapshot first = service.disconnect(principal(), SHARED);

        assertThat(first.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(first.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.FAILED, SETTINGS, null));
        verify(oauth, never()).revoke(any(), any(), any(), any());
        verify(hermes).deleteVault(mail.vault());

        ConnectionSnapshot last = service.disconnect(principal(), OTHER_SHARED);

        assertThat(last.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.REVOKED, null, null));
        verify(oauth).revoke(eq(calendar.vault()), eq(OTHER_SHARED), any(), any());
    }


    @Test
    @DisplayName("카탈로그에서 빠진 플러그인의 단추 연결은 취소를 부르지 않고 주소 없이 실패로 답하며 해제된다")
    void removedConnectorSkipsRevoke() {
        ConnectorConnection connection = connection(PLUGIN, new ConnectionAuth("github", false));
        when(hermes.readCatalog()).thenReturn(List.of());

        ConnectionSnapshot disconnected = service.disconnect(principal(), PLUGIN);

        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.oauthRevoke()).isEqualTo(new ConnectionOAuthRevoke(Status.FAILED, null, null));
        verify(oauth, never()).revoke(any(), any(), any(), any());
        verify(hermes).deleteVault(connection.vault());
    }


    @Test
    @DisplayName("바인딩을 떼지 못하면 허락을 취소하지 않고 연결은 준비 안 됨으로 남는다")
    void detachFailureDoesNotRevoke() {
        ConnectorConnection connection = connection(PLUGIN, new ConnectionAuth("github", false));
        String code = "revoke-" + UUID.randomUUID().toString().substring(0, 13);
        Agent agent = agents.save(Agent.of(
            code, code, "profile-" + code, "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id()));
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, "demo", clock.instant());
        binding.installed(false, clock.instant());
        binding.ready(clock.instant());
        bindings.save(binding);
        when(hermes.unbindConnector(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.disconnect(principal(), PLUGIN)).isInstanceOf(ConnectorOperationFailure.class);

        verify(oauth, never()).revoke(any(), any(), any(), any());
        verify(hermes, never()).deleteVault(anyString());
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.PENDING);
    }


    @Test
    @DisplayName("붙여 넣은 연결의 해제는 취소를 부르지 않고 취소 결과가 없다")
    void manualConnectionHasNoRevoke() {
        connection(PLUGIN, ConnectionAuth.MANUAL);

        ConnectionSnapshot disconnected = service.disconnect(principal(), PLUGIN);

        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.oauthRevoke()).isNull();
        verify(oauth, never()).revoke(any(), any(), any(), any());
    }


    private CurrentUser principal() {
        return new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
    }


    private ConnectorConnection connection(String connectorId, ConnectionAuth auth) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), connectorId, clock.instant());
        connection.connected(new ConnectionFields(Map.of(), Map.of(), auth), clock.instant());
        return connections.save(connection);
    }


    private AppUser user(UserRole role, long group) {
        return users.save(AppUser.of("oauth-revoke-" + UUID.randomUUID() + "@example.test", "검사 사용자", group, role));
    }


    private static ConnectorManifest manifest(String id, ConnectorOAuthDeclaration oauth) {
        List<ConnectorField> fields = List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            id, "데모 " + id, "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null, oauth);
    }
}
