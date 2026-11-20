package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthRefresher;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthTokens;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthToken;
import com.bifos.assistant.connector.oauth.presentation.ConnectorTokenController;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 토큰 통로와 갱신, 끊김 알림을 대시보드 대역으로 확인한다(스펙 REQ6, REQ7). */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorOAuthTokenTest {
    private static final String TOKEN_BASE = "http://cp.example.test";
    private static final String CONNECTOR = "demo-notes";
    private static final String CLIENT_SECRET = "client-secret-for-test-123";
    private static final String ACCESS_TOKEN = "access-token-for-test-1";

    @Autowired
    ConnectorTokenController controller;

    @Autowired
    ConnectorOAuthTokens tokens;

    @Autowired
    ConnectorOAuthRefresher refresher;

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
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private MockMvc mvc;
    private AppUser owner;
    private AppUser admin;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(hermes.readCatalog()).thenReturn(List.of(manifest()));
        // 다른 검사가 남긴 관리자와 섞이지 않게 검사마다 그룹을 따로 쓴다.
        long group = 900_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 90_000L);
        owner = user(UserRole.MEMBER, group);
        admin = user(UserRole.ADMIN, group);
        apps.save(CONNECTOR, "example-client", CLIENT_SECRET, "demo-app", admin.id());
    }


    @Test
    @DisplayName("통로는 맞는 표식에 지금 유효한 토큰을 주고 같은 토큰을 다시 갱신하지 않는다")
    void passageGivesValidTokenWithoutRefreshingAgain() throws Exception {
        ConnectorConnection connection = oauthConnection();
        Instant expiresAt = clock.instant().plus(Duration.ofHours(8));
        whenRefresh(connection).thenReturn(new Token(Outcome.OK, ACCESS_TOKEN, expiresAt));

        for (int call = 0; call < 2; call++) {
            mvc.perform(get(currentPath(connection)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.access_token").value(ACCESS_TOKEN))
                .andExpect(jsonPath("$.expires_at").value(expiresAt.getEpochSecond()));
        }
        verify(oauth, times(1)).refresh(eq(connection.vault()), eq(CONNECTOR), any());
        String stored = jdbc.queryForObject("select fields from connector_connection where id = ?", String.class, connection.id());
        assertThat(stored).doesNotContain(ACCESS_TOKEN);
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.READY);
    }


    @Test
    @DisplayName("번호를 바꾸거나 위조한 표식은 401 이고 대기 표식은 그 흐름의 대기 칸만 준다")
    void rejectsTamperedMarksAndServesPendingSlotOnly() throws Exception {
        ConnectorConnection connection = oauthConnection();
        ConnectorConnection other = oauthConnection();
        String mark = currentPath(connection);
        String signature = mark.substring(mark.lastIndexOf('.'));
        mvc.perform(get("/internal/connector-token/k" + other.id() + signature))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(get("/internal/connector-token/k" + connection.id() + "." + "0".repeat(64))).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/connector-token/not-a-mark")).andExpect(status().isUnauthorized());
        verify(oauth, never()).refresh(any(), any(), any());

        String pending = signer.pendingUrl(connection.id(), "flow-nonce-1").orElseThrow().substring(TOKEN_BASE.length());
        mvc.perform(get(pending)).andExpect(status().isNotFound());
        tokens.putPending(connection.id(), "flow-nonce-1", new ConnectorOAuthToken("pending-token-for-test", null));
        mvc.perform(get(pending))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value("pending-token-for-test"))
            .andExpect(jsonPath("$.expires_at").isEmpty());
        mvc.perform(get(signer.pendingUrl(connection.id(), "flow-nonce-2").orElseThrow().substring(TOKEN_BASE.length())))
            .andExpect(status().isNotFound());
        assertThat(tokens.current(connection.id())).as("확인 전 토큰은 현재 칸에 가지 않는다").isEmpty();
    }


    @Test
    @DisplayName("해제한 연결과 붙여 넣은 연결의 표식은 404 다")
    void disconnectedAndManualConnectionsGetNotFound() throws Exception {
        ConnectorConnection disconnected = oauthConnection();
        disconnected.disconnected(clock.instant());
        connections.save(disconnected);
        ConnectorConnection manual = connection(ConnectionAuth.MANUAL);

        mvc.perform(get(currentPath(disconnected))).andExpect(status().isNotFound());
        mvc.perform(get(currentPath(manual))).andExpect(status().isNotFound());
        verify(oauth, never()).refresh(any(), any(), any());
    }


    @Test
    @DisplayName("서비스가 갱신을 거절하면 다시 연결 필요로 바꾸고 알림은 한 번만 남긴다")
    void grantRevokedMarksReconnectAndNotifiesOnce() throws Exception {
        ConnectorConnection connection = oauthConnection();
        whenRefresh(connection).thenReturn(new Token(Outcome.GRANT_REVOKED, null, null));

        assertThat(tokens.refresh(connection.id(), null).outcome()).isEqualTo(Outcome.GRANT_REVOKED);
        assertThat(tokens.refresh(connection.id(), null).outcome()).isEqualTo(Outcome.GRANT_REVOKED);

        ConnectorConnection saved = connections.findById(connection.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(saved.fields().auth()).isEqualTo(new ConnectionAuth("github", true));
        List<Notification> sent = notificationsOf(owner);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().kind()).isEqualTo(NotificationKind.CONNECTOR_RECONNECT_REQUIRED);
        assertThat(sent.getFirst().targetType()).isEqualTo(NotificationTargetType.CONNECTIONS);
        assertThat(sent.getFirst().body()).contains("데모 노트");
        assertThat(notificationsOf(admin)).isEmpty();
        mvc.perform(get(currentPath(connection))).andExpect(status().isConflict());
    }


    @Test
    @DisplayName("서비스가 앱 자격을 거절하면 연결은 두고 관리자에게만 한 번 알리며 앱을 바꾸면 읽음이 된다")
    void appRejectedKeepsConnectionAndNotifiesAdminsOnce() throws Exception {
        ConnectorConnection connection = oauthConnection();
        whenRefresh(connection).thenReturn(new Token(Outcome.APP_REJECTED, null, null));

        mvc.perform(get(currentPath(connection))).andExpect(status().isServiceUnavailable());
        assertThat(tokens.refresh(connection.id(), null).outcome()).isEqualTo(Outcome.APP_REJECTED);

        ConnectorConnection saved = connections.findById(connection.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(saved.fields().auth().reconnectRequired()).isFalse();
        assertThat(notificationsOf(owner)).isEmpty();
        List<Notification> sent = notificationsOf(admin);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().kind()).isEqualTo(NotificationKind.CONNECTOR_OAUTH_APP_REJECTED);
        assertThat(sent.getFirst().targetType()).isEqualTo(NotificationTargetType.ADMIN_SECRETS);
        assertThat(sent.getFirst().unread()).isTrue();
        assertThat(sent.getFirst().body()).doesNotContain(CLIENT_SECRET);

        apps.save(CONNECTOR, "example-client", CLIENT_SECRET + "-new", "demo-app", admin.id());
        assertThat(notificationsOf(admin).getFirst().unread()).isFalse();
        tokens.refresh(connection.id(), null);
        assertThat(notificationsOf(admin)).as("읽은 뒤 다시 거절되면 다시 알린다").hasSize(2);
    }


    @Test
    @DisplayName("대시보드나 서비스가 잠시 답하지 않으면 상태와 알림을 바꾸지 않는다")
    void unavailableChangesNothing() throws Exception {
        ConnectorConnection connection = oauthConnection();
        whenRefresh(connection)
            .thenReturn(new Token(Outcome.UNAVAILABLE, null, null))
            .thenThrow(new IllegalStateException("boom " + CLIENT_SECRET));

        mvc.perform(get(currentPath(connection))).andExpect(status().isServiceUnavailable());
        mvc.perform(get(currentPath(connection))).andExpect(status().isServiceUnavailable());

        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.READY);
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(notificationsOf(admin)).isEmpty();
    }


    @Test
    @DisplayName("앱 값이 없으면 대시보드를 부르지 않고 그 호출만 실패한다")
    void missingAppFailsOnlyThatCall() throws Exception {
        ConnectorConnection connection = oauthConnection();
        apps.clear(CONNECTOR, admin.id());

        mvc.perform(get(currentPath(connection))).andExpect(status().isServiceUnavailable());

        verify(oauth, never()).refresh(any(), any(), any());
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.READY);
    }


    @Test
    @DisplayName("OAuth 파일이 없다는 답이면 현재 칸을 지우고 404 다")
    void missingFileDropsCurrentSlot() throws Exception {
        ConnectorConnection connection = oauthConnection();
        whenRefresh(connection)
            .thenReturn(new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plusSeconds(30)))
            .thenReturn(new Token(Outcome.MISSING, null, null));

        mvc.perform(get(currentPath(connection))).andExpect(status().isOk());
        mvc.perform(get(currentPath(connection))).andExpect(status().isNotFound());

        assertThat(tokens.current(connection.id())).isEmpty();
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.READY);
    }


    @Test
    @DisplayName("같은 연결에 동시에 온 두 요청은 대시보드 갱신을 한 번만 부른다")
    void concurrentRequestsRefreshOnce() throws Exception {
        ConnectorConnection connection = oauthConnection();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        whenRefresh(connection).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plus(Duration.ofHours(1)));
        });

        CompletableFuture<Outcome> first = CompletableFuture.supplyAsync(
            () -> tokens.refresh(connection.id(), Duration.ofMinutes(1)).outcome());
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Outcome> second = CompletableFuture.supplyAsync(
            () -> tokens.refresh(connection.id(), Duration.ofMinutes(1)).outcome());
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.OK);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.OK);
        verify(oauth, times(1)).refresh(eq(connection.vault()), eq(CONNECTOR), any());
    }


    @Test
    @DisplayName("예약 갱신은 쓸 수 있는 단추 연결 가운데 칸이 없거나 15분 안에 만료되는 것만 갱신한다")
    void scheduledRefreshPicksDueOAuthConnections() {
        ConnectorConnection empty = oauthConnection();
        ConnectorConnection expiring = oauthConnection();
        ConnectorConnection fresh = oauthConnection();
        ConnectorConnection manual = connection(ConnectionAuth.MANUAL);
        ConnectorConnection revoked = oauthConnection();
        revoked.reconnectRequired(clock.instant());
        connections.save(revoked);
        Instant now = clock.instant();
        when(oauth.refresh(any(), eq(CONNECTOR), any())).thenAnswer(call -> {
            String vault = call.getArgument(0);
            Duration life = vault.equals(expiring.vault()) ? Duration.ofMinutes(10) : Duration.ofHours(1);
            return new Token(Outcome.OK, ACCESS_TOKEN, now.plus(life));
        });
        tokens.refresh(expiring.id(), null);
        tokens.refresh(fresh.id(), null);

        refresher.refreshDue();

        verify(oauth, times(1)).refresh(eq(empty.vault()), eq(CONNECTOR), any());
        verify(oauth, times(2)).refresh(eq(expiring.vault()), eq(CONNECTOR), any());
        verify(oauth, times(1)).refresh(eq(fresh.vault()), eq(CONNECTOR), any());
        verify(oauth, never()).refresh(eq(manual.vault()), any(), any());
        verify(oauth, never()).refresh(eq(revoked.vault()), any(), any());
        assertThat(tokens.current(empty.id())).isPresent();
    }


    private OngoingStubbing<Token> whenRefresh(ConnectorConnection connection) {
        return when(oauth.refresh(eq(connection.vault()), eq(CONNECTOR), any()));
    }


    private String currentPath(ConnectorConnection connection) {
        return signer.currentUrl(connection.id()).orElseThrow().substring(TOKEN_BASE.length());
    }


    private ConnectorConnection oauthConnection() {
        return connection(new ConnectionAuth("github", false));
    }


    /** 연결은 사용자와 플러그인마다 하나라 연결마다 주인을 따로 만든다. 첫 연결만 {@code owner} 의 것이다. */
    private ConnectorConnection connection(ConnectionAuth auth) {
        AppUser user = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).isEmpty()
            ? owner : user(UserRole.MEMBER, owner.groupId());
        ConnectorConnection connection = ConnectorConnection.pending(user.id(), CONNECTOR, clock.instant());
        connection.connected(new ConnectionFields(Map.of(), Map.of(), auth), clock.instant());
        return connections.save(connection);
    }


    private List<Notification> notificationsOf(AppUser user) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.ofSize(20));
    }


    private AppUser user(UserRole role, long group) {
        return users.save(AppUser.of("oauth-token-" + UUID.randomUUID() + "@example.test", "검사 사용자", group, role));
    }


    private static ConnectorManifest manifest() {
        List<ConnectorField> fields = List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            CONNECTOR, "데모 노트", "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null,
            new ConnectorOAuthDeclaration("github", AppScope.PLUGIN, List.of("token"), "https://accounts.example.test/settings", null));
    }
}
