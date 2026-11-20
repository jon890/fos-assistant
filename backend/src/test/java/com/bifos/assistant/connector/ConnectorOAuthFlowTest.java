package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.connection.presentation.ConnectorConnectionController;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthTokens;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthController;
import com.bifos.assistant.connector.oauth.presentation.ConnectorTokenController;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Authorization;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Exchange;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Mode;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.VaultAccess;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.ConnectorOAuthFlowConflict;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 단추 연결의 시작과 돌아오기를 대시보드 대역으로 확인한다(스펙 REQ3, REQ4, REQ5, REQ9, REQ11). */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorOAuthFlowTest {
    private static final String TOKEN_BASE = "http://cp.example.test";
    private static final String CONNECTOR = "demo-notes";
    private static final String PLAIN = "demo-plain";
    private static final String REDIRECT = "https://web.example.test/connections/oauth/callback";
    private static final String CLIENT_SECRET = "client-secret-for-test-123";
    private static final String ACCESS_TOKEN = "access-token-for-test-1";
    private static final String CODE = "code-for-test-1";
    private static final String NONCE = "nonce-for-test-1";
    private static final String LOGIN = "https://accounts.example.test/login";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ConnectorOAuthController controller;

    @Autowired
    ConnectorConnectionController connectionController;

    @Autowired
    ConnectorTokenController tokenController;

    @Autowired
    ConnectorOAuthApps apps;

    @Autowired
    ConnectorOAuthTokens tokens;

    @Autowired
    ConnectorOAuthSigner signer;

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
    TestClock clock;

    private MockMvc mvc;
    private AppUser owner;
    private AppUser admin;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller, connectionController, tokenController)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(hermes.readCatalog()).thenReturn(List.of(manifest(CONNECTOR, true), manifest(PLAIN, false)));
        when(hermes.callWithVault(eq(CONNECTOR), eq("verify"), any(), any(), any()))
            .thenReturn(CallResult.success(JSON.createObjectNode()));
        when(oauth.begin(any(), eq(CONNECTOR), any())).thenReturn(NONCE);
        when(oauth.authorizeUrl(any())).thenReturn(LOGIN);
        when(oauth.exchange(any())).thenReturn(new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plus(Duration.ofHours(8))));
        // 다른 검사가 남긴 관리자와 섞이지 않게 검사마다 그룹을 따로 쓴다.
        long group = 800_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 90_000L);
        admin = user(UserRole.ADMIN, group);
        owner = user(UserRole.MEMBER, group);
        apps.save(CONNECTOR, "example-client", CLIENT_SECRET, "demo-app", admin.id());
        signIn(owner);
    }


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        clock.reset();
    }


    @Test
    @DisplayName("MEMBER 역할 사용자가 단추로 연결하면 교환, 확인, commit 순서로 돌고 토큰은 통로로만 나간다")
    void memberConnectsThroughButton(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/connectors")).andExpect(status().isOk()).andExpect(jsonPath("$[0].oauth.ready").value(true));

        String startBody = mvc.perform(start(CONNECTOR, REDIRECT, "{\"repo\":\"me/notes\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorizeUrl").value(LOGIN))
            .andReturn().getResponse().getContentAsString();
        ConnectorConnection reserved = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow();
        assertThat(reserved.status()).as("끝나기 전에는 연결로 보이지 않는다").isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(oauth).begin(reserved.vault(), CONNECTOR, Map.of("repo", "me/notes"));
        Authorization sent = authorization();
        assertThat(sent.mode()).isEqualTo(Mode.INSTALL);
        assertThat(sent.appSlug()).isEqualTo("demo-app");
        assertThat(sent.clientId()).isEqualTo("example-client");
        assertThat(sent.redirectUri()).isEqualTo(REDIRECT);
        assertThat(sent.codeChallenge()).as("설치 화면은 challenge 를 받지 않는다").isNull();
        assertThat(sent.state()).startsWith("v1." + owner.id() + "." + CONNECTOR + ".");

        String completeBody = mvc.perform(complete(sent.state(), CODE, null, "install"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CONNECTED"))
            .andExpect(jsonPath("$.connectorId").value(CONNECTOR))
            .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
        InOrder order = inOrder(oauth, hermes);
        order.verify(oauth).exchange(exchange.capture());
        order.verify(hermes).callWithVault(CONNECTOR, "verify", reserved.vault(), null, VaultAccess.PENDING);
        ArgumentCaptor<String> currentUrl = ArgumentCaptor.forClass(String.class);
        order.verify(oauth).commit(eq(reserved.vault()), eq(NONCE), currentUrl.capture());
        assertThat(exchange.getValue().code()).isEqualTo(CODE);
        assertThat(exchange.getValue().nonce()).isEqualTo(NONCE);
        assertThat(exchange.getValue().codeVerifier()).isNull();
        assertThat(exchange.getValue().redirectUri()).isEqualTo(REDIRECT);
        assertThat(exchange.getValue().credentials().clientSecret()).isEqualTo(CLIENT_SECRET);
        assertThat(exchange.getValue().tokenUrl()).startsWith(TOKEN_BASE + "/internal/connector-token/p" + reserved.id() + "." + NONCE + ".");
        assertThat(currentUrl.getValue()).isEqualTo(signer.currentUrl(reserved.id()).orElseThrow());
        verify(oauth, never()).discard(any(), any(), any(), any(), any());

        String view = mvc.perform(get("/api/v1/connections/" + CONNECTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.auth.method").value("oauth"))
            .andExpect(jsonPath("$.auth.provider").value("github"))
            .andExpect(jsonPath("$.auth.reconnectRequired").value(false))
            .andExpect(jsonPath("$.values.repo").value("me/notes"))
            .andExpect(jsonPath("$.secretPrefixes.token").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        mvc.perform(get(currentUrl.getValue().substring(TOKEN_BASE.length())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(ACCESS_TOKEN));
        verify(oauth, never()).refresh(any(), any(), any());

        assertThat(startBody + completeBody + view).doesNotContain(ACCESS_TOKEN, CLIENT_SECRET, CODE);
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN, CLIENT_SECRET, CODE);
        mvc.perform(complete(sent.state(), CODE, null, null))
            .andExpect(jsonPath("$.outcome").value("INVALID_STATE"))
            .andExpect(jsonPath("$.connectorId").isEmpty());
        verify(oauth, times(1)).exchange(any());
    }


    @Test
    @DisplayName("위조했거나 만료됐거나 다른 사용자가 시작한 상태 값은 받지 않고 주인의 흐름은 남는다")
    void rejectsForgedExpiredAndForeignStates() throws Exception {
        String state = started("{\"repo\":\"me/notes\"}");
        String forged = state.substring(0, state.length() - 64) + "0".repeat(64);
        mvc.perform(complete(forged, CODE, null, null)).andExpect(jsonPath("$.outcome").value("INVALID_STATE"));
        mvc.perform(complete("not-a-state", CODE, null, null)).andExpect(jsonPath("$.outcome").value("INVALID_STATE"));
        mvc.perform(complete(null, CODE, null, null)).andExpect(jsonPath("$.outcome").value("INVALID_STATE"));

        signIn(user(UserRole.MEMBER, owner.groupId()));
        mvc.perform(complete(state, CODE, null, null)).andExpect(jsonPath("$.outcome").value("INVALID_STATE"));
        verify(oauth, never()).exchange(any());
        verify(oauth, never()).discard(any(), any(), any(), any(), any());

        signIn(owner);
        clock.set(clock.instant().plus(ConnectorOAuthSigner.STATE_TTL));
        mvc.perform(complete(state, CODE, null, null)).andExpect(jsonPath("$.outcome").value("INVALID_STATE"));
        verify(oauth, never()).exchange(any());
        clock.reset();

        mvc.perform(complete(state, CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));
    }


    @Test
    @DisplayName("서비스 화면에서 거절하면 아무것도 저장하지 않고 대기 파일을 지운다")
    void deniedStoresNothing() throws Exception {
        String state = started("{\"repo\":\"me/notes\"}");

        mvc.perform(complete(state, null, "access_denied", null))
            .andExpect(jsonPath("$.outcome").value("DENIED"))
            .andExpect(jsonPath("$.connectorId").value(CONNECTOR));

        ConnectorConnection connection = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow();
        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(oauth).discard(eq(connection.vault()), eq(CONNECTOR), eq(NONCE), any(), isNull());
        verify(oauth, never()).exchange(any());
        mvc.perform(get("/api/v1/connectors")).andExpect(jsonPath("$[0].myStatus").value("DISCONNECTED"));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), null, null, null)).andExpect(jsonPath("$.outcome").value("DENIED"));
    }


    @Test
    @DisplayName("교환이 실패하면 까닭을 구분해 답하고 앱 거절은 관리자에게 알린다")
    void exchangeFailuresAreToldApart() throws Exception {
        when(oauth.exchange(any())).thenReturn(new Token(Outcome.SCOPE_DENIED, null, null));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("SCOPE_DENIED"));
        when(oauth.exchange(any())).thenReturn(new Token(Outcome.GRANT_REVOKED, null, null));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("DENIED"));
        when(oauth.exchange(any())).thenReturn(new Token(Outcome.UNAVAILABLE, null, null));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("UNAVAILABLE"));
        when(oauth.exchange(any())).thenThrow(new IllegalStateException("boom " + CLIENT_SECRET));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("UNAVAILABLE"));
        assertThat(notificationsOf(admin)).isEmpty();
        verify(oauth, times(4)).discard(any(), eq(CONNECTOR), eq(NONCE), any(), isNull());

        Mockito.reset(oauth);
        when(oauth.begin(any(), eq(CONNECTOR), any())).thenReturn(NONCE);
        when(oauth.authorizeUrl(any())).thenReturn(LOGIN);
        when(oauth.exchange(any())).thenReturn(new Token(Outcome.APP_REJECTED, null, null));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("APP_REJECTED"));
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("APP_REJECTED"));
        List<Notification> sent = notificationsOf(admin);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().kind()).isEqualTo(NotificationKind.CONNECTOR_OAUTH_APP_REJECTED);
        assertThat(notificationsOf(owner)).isEmpty();

        when(oauth.exchange(any())).thenThrow(new ConnectorOAuthFlowConflict());
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("SUPERSEDED"));
        verify(oauth, times(2)).discard(any(), any(), any(), any(), any());
        verify(oauth, never()).commit(any(), any(), any());
        assertThat(connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow().status())
            .isEqualTo(ConnectionStatus.DISCONNECTED);
    }


    @Test
    @DisplayName("확인 도구가 실패하면 저장하지 않고 받은 토큰을 취소하며 까닭을 답한다")
    void verifyFailureDiscardsTheToken() throws Exception {
        when(hermes.callWithVault(eq(CONNECTOR), eq("verify"), any(), any(), any()))
            .thenReturn(CallResult.failure(ConnectorCallError.CREDENTIAL_REJECTED));
        String state = started("{\"repo\":\"me/notes\"}");

        mvc.perform(complete(state, CODE, null, null))
            .andExpect(jsonPath("$.outcome").value("VERIFY_FAILED"))
            .andExpect(jsonPath("$.reason").value("CONNECTOR_CREDENTIAL_REJECTED"));

        ConnectorConnection connection = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow();
        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(oauth).discard(eq(connection.vault()), eq(CONNECTOR), eq(NONCE), any(), eq(ACCESS_TOKEN));
        verify(oauth, never()).commit(any(), any(), any());
        assertThat(tokens.pending(connection.id(), NONCE)).isEmpty();
        assertThat(tokens.current(connection.id())).isEmpty();
    }


    @Test
    @DisplayName("이미 설치된 앱이라 코드 없이 돌아오면 PKCE 를 넣은 허락 화면으로 한 번 더 보낸다")
    void installedAppIsSentToAuthorizeWithPkce() throws Exception {
        String state = started("{\"repo\":\"me/notes\"}");
        when(oauth.authorizeUrl(any())).thenReturn(LOGIN + "/authorize");

        mvc.perform(complete(state, null, null, "update"))
            .andExpect(jsonPath("$.outcome").value("REAUTHORIZE"))
            .andExpect(jsonPath("$.authorizeUrl").value(LOGIN + "/authorize"));

        ArgumentCaptor<Authorization> sent = ArgumentCaptor.forClass(Authorization.class);
        verify(oauth, times(2)).authorizeUrl(sent.capture());
        Authorization again = sent.getAllValues().get(1);
        assertThat(again.mode()).isEqualTo(Mode.AUTHORIZE);
        assertThat(again.codeChallenge()).isNotBlank();
        assertThat(again.state()).isNotEqualTo(state);
        verify(oauth, times(1)).begin(any(), any(), any());

        mvc.perform(complete(again.state(), CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));
        ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
        verify(oauth).exchange(exchange.capture());
        assertThat(exchange.getValue().nonce()).isEqualTo(NONCE);
        assertThat(ConnectorOAuthSigner.pkceChallenge(exchange.getValue().codeVerifier())).isEqualTo(again.codeChallenge());
    }


    @Test
    @DisplayName("commit 이 409 면 기존 연결을 그대로 두고 그 밖의 commit 실패는 준비 안 됨으로 남긴다")
    void commitConflictKeepsExistingConnection() throws Exception {
        ConnectorConnection manual = manualConnection();
        doThrow(new ConnectorOAuthFlowConflict()).when(oauth).commit(any(), any(), any());

        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("SUPERSEDED"));

        ConnectorConnection kept = connections.findById(manual.id()).orElseThrow();
        assertThat(kept.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(kept.fields()).isEqualTo(manual.fields());
        assertThat(tokens.current(manual.id())).isEmpty();
        assertThat(tokens.pending(manual.id(), NONCE)).isEmpty();

        doThrow(new IllegalStateException("boom")).when(oauth).commit(any(), any(), any());
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("UNAVAILABLE"));
        assertThat(connections.findById(manual.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.PENDING);
    }


    @Test
    @DisplayName("붙여 넣은 연결에서 단추로 다시 연결하면 방식이 바뀌고 다시 붙여 넣으면 통로가 닫힌다")
    void switchesBetweenManualAndButton() throws Exception {
        ConnectorConnection manual = manualConnection();

        mvc.perform(complete(started("{\"repo\":\"me/other\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));
        ConnectorConnection switched = connections.findById(manual.id()).orElseThrow();
        assertThat(switched.fields().auth()).isEqualTo(new ConnectionAuth("github", false));
        assertThat(switched.fields().secretPrefixes()).doesNotContainKey("token");
        assertThat(switched.fields().values()).containsEntry("repo", "me/other");
        String mark = signer.currentUrl(manual.id()).orElseThrow().substring(TOKEN_BASE.length());
        mvc.perform(get(mark)).andExpect(status().isOk());

        when(hermes.call(eq(CONNECTOR), eq("verify"), any(), any())).thenReturn(CallResult.success(JSON.createObjectNode()));
        mvc.perform(post("/api/v1/connections/" + CONNECTOR).contentType(MediaType.APPLICATION_JSON)
            .content("{\"values\":{\"repo\":\"me/notes\",\"token\":\"demo_token_0123456789\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auth.method").value("manual"))
            .andExpect(jsonPath("$.secretPrefixes.token").value("demo"));
        assertThat(tokens.current(manual.id())).isEmpty();
        mvc.perform(get(mark)).andExpect(status().isNotFound());
        verify(oauth, never()).refresh(any(), any(), any());
    }


    @Test
    @DisplayName("다시 연결 필요인 연결을 단추로 다시 연결하면 쓸 수 있게 되고 끊김 알림이 읽음이 된다")
    void reconnectClearsTheFlagAndTheNotification() throws Exception {
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));
        ConnectorConnection connection = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow();
        when(oauth.refresh(eq(connection.vault()), eq(CONNECTOR), any())).thenReturn(new Token(Outcome.GRANT_REVOKED, null, null));
        tokens.refresh(connection.id(), null);
        assertThat(notificationsOf(owner)).hasSize(1).allMatch(Notification::unread);
        mvc.perform(get("/api/v1/connections/" + CONNECTOR))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.auth.reconnectRequired").value(true));

        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));

        mvc.perform(get("/api/v1/connections/" + CONNECTOR))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.auth.reconnectRequired").value(false));
        assertThat(notificationsOf(owner)).hasSize(1).noneMatch(Notification::unread);
        assertThat(tokens.current(connection.id())).isPresent();
    }


    @Test
    @DisplayName("단추 연결의 확인은 본 자리 토큰으로 확인 도구를 부르고 해제하면 통로가 닫히며 끊김 알림이 읽음이 된다")
    void checkUsesCurrentTokenAndDisconnectClosesThePassage() throws Exception {
        mvc.perform(complete(started("{\"repo\":\"me/notes\"}"), CODE, null, null)).andExpect(jsonPath("$.outcome").value("CONNECTED"));
        ConnectorConnection connection = connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR).orElseThrow();
        String mark = signer.currentUrl(connection.id()).orElseThrow().substring(TOKEN_BASE.length());

        mvc.perform(post("/api/v1/connections/" + CONNECTOR + "/check"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.auth.method").value("oauth"));
        verify(hermes).callWithVault(CONNECTOR, "verify", connection.vault(), null, VaultAccess.CURRENT);

        when(oauth.refresh(eq(connection.vault()), eq(CONNECTOR), any())).thenReturn(new Token(Outcome.GRANT_REVOKED, null, null));
        tokens.refresh(connection.id(), null);
        assertThat(notificationsOf(owner)).hasSize(1).allMatch(Notification::unread);

        mvc.perform(delete("/api/v1/connections/" + CONNECTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISCONNECTED"))
            .andExpect(jsonPath("$.auth.method").value("manual"));
        verify(hermes).deleteVault(connection.vault());
        assertThat(tokens.current(connection.id())).isEmpty();
        assertThat(notificationsOf(owner)).hasSize(1).noneMatch(Notification::unread);
        mvc.perform(get(mark)).andExpect(status().isNotFound());
    }


    @Test
    @DisplayName("앱 값이 없으면 단추를 내지 않고 시작을 409 로 거절하며 틀린 입력은 시작하지 않는다")
    void startRequiresConfiguredAppAndValidInput() throws Exception {
        mvc.perform(start(CONNECTOR, REDIRECT, "{\"repo\":\"me/notes\",\"token\":\"demo_token_0123456789\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(start(CONNECTOR, REDIRECT, "{}")).andExpect(status().isBadRequest());
        mvc.perform(start(CONNECTOR, "javascript:alert(1)", "{\"repo\":\"me/notes\"}")).andExpect(status().isBadRequest());
        mvc.perform(start(CONNECTOR, "/connections/oauth/callback", "{\"repo\":\"me/notes\"}")).andExpect(status().isBadRequest());
        mvc.perform(start(PLAIN, REDIRECT, "{\"repo\":\"me/notes\"}")).andExpect(status().isNotFound());
        mvc.perform(start("demo-unknown", REDIRECT, "{}")).andExpect(status().isNotFound());

        apps.clear(CONNECTOR, admin.id());
        mvc.perform(get("/api/v1/connectors")).andExpect(jsonPath("$[0].oauth.ready").value(false));
        mvc.perform(start(CONNECTOR, REDIRECT, "{\"repo\":\"me/notes\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONNECTOR_OAUTH_NOT_CONFIGURED"));

        verify(oauth, never()).begin(any(), any(), any());
        assertThat(connections.findByUserIdAndConnectorId(owner.id(), CONNECTOR)).isEmpty();
    }


    /** 흐름 하나를 시작하고 서비스로 보낸 상태 값을 돌려준다. */
    private String started(String values) throws Exception {
        mvc.perform(start(CONNECTOR, REDIRECT, values)).andExpect(status().isOk());
        return authorization().state();
    }


    private Authorization authorization() {
        ArgumentCaptor<Authorization> sent = ArgumentCaptor.forClass(Authorization.class);
        verify(oauth, atLeastOnce()).authorizeUrl(sent.capture());
        return sent.getAllValues().getLast();
    }


    private ConnectorConnection manualConnection() {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), CONNECTOR, clock.instant());
        connection.connected(new ConnectionFields(Map.of("repo", "me/notes"), Map.of("token", "demo")), clock.instant());
        return connections.save(connection);
    }


    private List<Notification> notificationsOf(AppUser user) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.ofSize(20));
    }


    private void signIn(AppUser user) {
        CurrentUser principal = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }


    private AppUser user(UserRole role, long group) {
        return users.save(AppUser.of("oauth-flow-" + UUID.randomUUID() + "@example.test", "검사 사용자", group, role));
    }


    private static MockHttpServletRequestBuilder start(
        String connector, String redirectUri, String values) {
        return post("/api/v1/connections/" + connector + "/oauth/start").contentType(MediaType.APPLICATION_JSON)
            .content("{\"redirectUri\":\"" + redirectUri + "\",\"values\":" + values + "}");
    }


    private static MockHttpServletRequestBuilder complete(
        String state, String code, String error, String setupAction) {
        JsonNode body = JSON.createObjectNode().put("state", state).put("code", code).put("error", error).put("setupAction", setupAction);
        return post("/api/v1/connections/oauth/complete").contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }


    private static ConnectorManifest manifest(String id, boolean button) {
        List<ConnectorField> fields = List.of(
            new ConnectorField("repo", "DEMO_REPO", "저장소", "", false, true, null, null),
            new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            id, "데모 노트", "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null,
            button ? new ConnectorOAuthDeclaration("github", AppScope.PLUGIN, List.of("token"), "https://accounts.example.test/settings", null) : null);
    }
}
