package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.dto.ConnectionSnapshot;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthTokens;
import com.bifos.assistant.connector.oauth.presentation.ConnectorTokenController;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.VaultAccess;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** 단추 연결의 「연결 다시 확인」 이 확인 도구 전에 토큰을 새로 받는지 대시보드 대역으로 확인한다(스펙 REQ7, DECISION19). */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorOAuthCheckTest {
    private static final String TOKEN_BASE = "http://cp.example.test";
    private static final String CONNECTOR = "demo-notes";
    private static final String ACCESS_TOKEN = "access-token-for-test-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ConnectorConnectionService service;

    @Autowired
    ConnectorTokenController passage;

    @Autowired
    ConnectorOAuthTokens tokens;

    @Autowired
    ConnectorOAuthSigner signer;

    @Autowired
    ConnectorOAuthApps apps;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesConnectorClient hermes;

    @Autowired
    HermesConnectorOAuthClient oauth;

    @Autowired
    Clock clock;

    private AppUser owner;
    private AppUser admin;


    @BeforeEach
    void setUp() {
        when(hermes.readCatalog()).thenReturn(List.of(manifest()));
        when(hermes.callWithVault(eq(CONNECTOR), eq("verify"), any(), any(), any()))
            .thenReturn(CallResult.success(JSON.createObjectNode()));
        when(hermes.callWithVault(eq(CONNECTOR), eq("verify"), any(), any()))
            .thenReturn(CallResult.success(JSON.createObjectNode()));
        // 다른 검사가 남긴 관리자와 섞이지 않게 검사마다 그룹을 따로 쓴다.
        long group = 900_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 90_000L);
        owner = user(UserRole.MEMBER, group);
        admin = user(UserRole.ADMIN, group);
        apps.save(CONNECTOR, "example-client", "client-secret-for-test-123", "demo-app", admin.id());
    }


    @Test
    @DisplayName("단추 연결의 다시 확인은 토큰을 새로 받은 뒤에 확인 도구를 부른다")
    void refreshesBeforeTheVerifyTool() {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        // 이미 넉넉히 남은 토큰이 있어도 다시 확인은 새로 받는다.
        stubRefresh(connection, Outcome.OK);
        tokens.refresh(connection.id(), null);

        ConnectionSnapshot checked = service.check(principal(), CONNECTOR);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        InOrder order = inOrder(oauth, hermes);
        order.verify(oauth, times(2)).refresh(eq(connection.vault()), eq(CONNECTOR), any());
        order.verify(hermes).callWithVault(CONNECTOR, "verify", connection.vault(), null, VaultAccess.CURRENT);
    }


    @Test
    @DisplayName("다시 연결 필요인 연결이 새 토큰을 받으면 표시가 지워지고 끊김 알림이 읽음이 되며 확인 도구가 통로에서 토큰을 받는다")
    void recoversReconnectRequiredConnection() throws Exception {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        stubRefresh(connection, Outcome.GRANT_REVOKED);
        tokens.refresh(connection.id(), null);
        assertThat(notificationsOf(owner)).hasSize(1).allMatch(Notification::unread);
        stubRefresh(connection, Outcome.OK);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(passage).build();
        String mark = signer.currentUrl(connection.id()).orElseThrow().substring(TOKEN_BASE.length());
        // 확인 도구가 도는 시점에 통로가 토큰을 주는지 그 자리에서 본다.
        when(hermes.callWithVault(eq(CONNECTOR), eq("verify"), any(), any(), any())).thenAnswer(call -> {
            mvc.perform(get(mark)).andExpect(status().isOk()).andExpect(jsonPath("$.access_token").value(ACCESS_TOKEN));
            return CallResult.success(JSON.createObjectNode());
        });

        ConnectionSnapshot checked = service.check(principal(), CONNECTOR);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(checked.auth()).isEqualTo(new ConnectionAuth("github", false));
        assertThat(notificationsOf(owner)).hasSize(1).noneMatch(Notification::unread);
        verify(hermes).callWithVault(CONNECTOR, "verify", connection.vault(), null, VaultAccess.CURRENT);
    }


    @Test
    @DisplayName("갱신하는 사이 연결이 바뀌었으면 갱신이 성공해도 다시 연결 필요 표시와 끊김 알림을 그대로 둔다")
    void recoveryKeepsANewerReconnectState() {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        stubRefresh(connection, Outcome.GRANT_REVOKED);
        tokens.refresh(connection.id(), null);
        // 갱신 응답을 기다리는 사이 다른 경로가 연결을 바꾼 것으로 본다(revision 이 바뀐다).
        when(oauth.refresh(eq(connection.vault()), eq(CONNECTOR), any())).thenAnswer(call -> {
            ConnectorConnection changed = connections.findById(connection.id()).orElseThrow();
            changed.pending(clock.instant());
            connections.save(changed);
            return new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plus(Duration.ofHours(8)));
        });

        tokens.refresh(connection.id(), null);

        assertThat(connections.findById(connection.id()).orElseThrow().fields().auth().reconnectRequired()).isTrue();
        assertThat(notificationsOf(owner)).hasSize(1).allMatch(Notification::unread);
    }


    @Test
    @DisplayName("서비스가 갱신을 거절하면 확인 도구를 부르지 않고 자격 거절로 끝내며 다시 연결 필요로 남는다")
    void grantRevokedSkipsTheVerifyTool() {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        stubRefresh(connection, Outcome.GRANT_REVOKED);

        assertCode(ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);
        assertCode(ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);

        verify(hermes, never()).callWithVault(any(), any(), any(), any(), any());
        ConnectorConnection saved = connections.findById(connection.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(saved.fields().auth()).isEqualTo(new ConnectionAuth("github", true));
        assertThat(notificationsOf(owner)).as("같은 끊김에 알림은 하나다").hasSize(1);
    }


    @Test
    @DisplayName("서비스가 잠시 답하지 않으면 확인 도구를 부르지 않고 잠시 뒤 다시 오류로 끝내며 연결은 그대로다")
    void unavailableKeepsTheConnection() {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        stubRefresh(connection, Outcome.UNAVAILABLE);

        assertCode(ErrorCode.CONNECTOR_UNAVAILABLE);

        verify(hermes, never()).callWithVault(any(), any(), any(), any(), any());
        ConnectorConnection saved = connections.findById(connection.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(saved.updatedAt()).isEqualTo(connection.updatedAt());
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(notificationsOf(admin)).isEmpty();
    }


    @Test
    @DisplayName("서비스가 앱 자격을 거절하면 연결은 그대로 두고 같은 오류로 끝내며 관리자에게만 알린다")
    void appRejectedKeepsTheConnectionAndNotifiesAdmins() {
        ConnectorConnection connection = connection(new ConnectionAuth("github", false));
        stubRefresh(connection, Outcome.APP_REJECTED);

        assertCode(ErrorCode.CONNECTOR_UNAVAILABLE);

        verify(hermes, never()).callWithVault(any(), any(), any(), any(), any());
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.READY);
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(notificationsOf(admin)).hasSize(1)
            .allMatch(sent -> sent.kind() == NotificationKind.CONNECTOR_OAUTH_APP_REJECTED);
    }


    @Test
    @DisplayName("붙여 넣은 연결의 다시 확인은 갱신을 부르지 않는다")
    void manualConnectionDoesNotRefresh() {
        ConnectorConnection connection = connection(ConnectionAuth.MANUAL);

        assertThat(service.check(principal(), CONNECTOR).status()).isEqualTo(ConnectionStatus.READY);

        verify(oauth, never()).refresh(any(), any(), any());
        verify(hermes).callWithVault(CONNECTOR, "verify", connection.vault(), null);
    }


    private void stubRefresh(ConnectorConnection connection, Outcome outcome) {
        Instant expiresAt = clock.instant().plus(Duration.ofHours(8));
        when(oauth.refresh(eq(connection.vault()), eq(CONNECTOR), any()))
            .thenReturn(outcome == Outcome.OK ? new Token(outcome, ACCESS_TOKEN, expiresAt) : new Token(outcome, null, null));
    }


    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> service.check(principal(), CONNECTOR))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }


    private CurrentUser principal() {
        return new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
    }


    private ConnectorConnection connection(ConnectionAuth auth) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), CONNECTOR, clock.instant());
        connection.connected(new ConnectionFields(Map.of(), Map.of(), auth), clock.instant());
        return connections.save(connection);
    }


    private List<Notification> notificationsOf(AppUser user) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.ofSize(20));
    }


    private AppUser user(UserRole role, long group) {
        return users.save(AppUser.of("oauth-check-" + UUID.randomUUID() + "@example.test", "검사 사용자", group, role));
    }


    private static ConnectorManifest manifest() {
        List<ConnectorField> fields = List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            CONNECTOR, "데모 노트", "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null,
            new ConnectorOAuthDeclaration("github", AppScope.PLUGIN, List.of("token"), "https://accounts.example.test/settings", null));
    }
}
