package com.bifos.assistant.admin.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthSigningAdminController;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthApps;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.connector.oauth.presentation.ConnectorTokenController;
import com.bifos.assistant.crypto.domain.ServerSecrets;
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
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 관리자가 단추 연결의 서명 비밀을 새로 만들면 통로 주소가 다시 쓰이는지 대시보드 대역으로 확인한다(스펙 REQ6). */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorOAuthSigningAdminControllerTest {
    private static final String SIGNING = "/api/v1/admin/secrets/connector-oauth/signing";
    private static final String TOKEN_BASE = "http://cp.example.test";
    private static final String CONNECTOR = "demo-notes";
    private static final String ACCESS_TOKEN = "access-token-for-test-1";

    @Autowired
    ConnectorOAuthSigningAdminController controller;

    @Autowired
    ConnectorTokenController passage;

    @Autowired
    ConnectorOAuthSigner signer;

    @Autowired
    ConnectorOAuthApps apps;

    @Autowired
    ServerSecrets secrets;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesConnectorClient hermes;

    @Autowired
    HermesConnectorOAuthClient oauth;

    @Autowired
    Clock clock;

    private MockMvc mvc;
    private AppUser admin;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller, passage).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(hermes.readCatalog()).thenReturn(List.of(manifest()));
        // 다른 검사가 남긴 단추 연결도 대상이다. 그 연결의 쓰기는 성공으로 둔다.
        when(oauth.replaceTokenUrl(anyString(), anyString())).thenReturn(Outcome.OK);
        admin = signIn(UserRole.ADMIN);
        apps.save(CONNECTOR, "example-client", "client-secret-for-test-123", "demo-app", admin.id());
    }


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("새로 만들면 단추 연결마다 새 통로 주소를 쓰고 옛 주소는 401 이며 붙여 넣은 연결과 해제한 연결에는 쓰지 않는다")
    void regenerateRewritesEveryOAuthConnection() throws Exception {
        ConnectorConnection ready = connection(new ConnectionAuth("github", false));
        ConnectorConnection revoked = connection(new ConnectionAuth("github", false));
        revoked.reconnectRequired(clock.instant());
        connections.save(revoked);
        ConnectorConnection manual = connection(ConnectionAuth.MANUAL);
        ConnectorConnection disconnected = connection(new ConnectionAuth("github", false));
        disconnected.disconnected(clock.instant());
        connections.save(disconnected);
        ConnectorConnection reserved = connections.save(ConnectorConnection.reserved(owner().id(), CONNECTOR, clock.instant()));
        String oldUrl = signer.currentUrl(ready.id()).orElseThrow();
        String oldSecret = secrets.read(ConnectorOAuthSigner.SECRET_NAME).orElseThrow();
        when(oauth.refresh(eq(ready.vault()), eq(CONNECTOR), any()))
            .thenReturn(new Token(Outcome.OK, ACCESS_TOKEN, clock.instant().plus(Duration.ofHours(8))));
        mvc.perform(get(oldUrl.substring(TOKEN_BASE.length()))).andExpect(status().isOk());
        int before = statusCount();

        String body = mvc.perform(post(SIGNING + "/regenerate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signing.set").value(true))
            .andExpect(jsonPath("$.signing.updatedBy.id").value(admin.id()))
            .andExpect(jsonPath("$.signing.connectionCount").value(before))
            .andExpect(jsonPath("$.rewrite.total").value(before))
            .andExpect(jsonPath("$.rewrite.failed").value(0))
            .andReturn().getResponse().getContentAsString();

        String newSecret = secrets.read(ConnectorOAuthSigner.SECRET_NAME).orElseThrow();
        assertThat(newSecret).matches("[0-9a-f]{64}").isNotEqualTo(oldSecret);
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(oauth).replaceTokenUrl(eq(ready.vault()), written.capture());
        String newUrl = written.getValue();
        assertThat(newUrl).isNotEqualTo(oldUrl).isEqualTo(signer.currentUrl(ready.id()).orElseThrow());
        assertThat(body).doesNotContain(newSecret).doesNotContain(newUrl);
        verify(oauth).replaceTokenUrl(eq(revoked.vault()), anyString());
        verify(oauth, never()).replaceTokenUrl(eq(manual.vault()), anyString());
        verify(oauth, never()).replaceTokenUrl(eq(disconnected.vault()), anyString());
        verify(oauth, never()).replaceTokenUrl(eq(reserved.vault()), anyString());
        mvc.perform(get(newUrl.substring(TOKEN_BASE.length())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(ACCESS_TOKEN));
        mvc.perform(get(oldUrl.substring(TOKEN_BASE.length()))).andExpect(status().isUnauthorized());
    }


    @Test
    @DisplayName("한 연결의 주소 쓰기가 실패해도 나머지는 쓰고 실패한 수를 답한다")
    void countsFailedRewritesAndContinues() throws Exception {
        ConnectorConnection broken = connection(new ConnectionAuth("github", false));
        ConnectorConnection missing = connection(new ConnectionAuth("github", false));
        ConnectorConnection fine = connection(new ConnectionAuth("github", false));
        when(oauth.replaceTokenUrl(eq(broken.vault()), anyString())).thenThrow(new IllegalStateException("boom"));
        when(oauth.replaceTokenUrl(eq(missing.vault()), anyString())).thenReturn(Outcome.MISSING);
        int before = statusCount();

        mvc.perform(post(SIGNING + "/regenerate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rewrite.total").value(before))
            .andExpect(jsonPath("$.rewrite.failed").value(2));

        verify(oauth).replaceTokenUrl(eq(fine.vault()), anyString());
    }


    @Test
    @DisplayName("상태는 원문 없이 입력 여부와 단추 연결 수를 주고 비관리자는 읽지도 새로 만들지도 못한다")
    void statusHidesTheSecretAndRejectsNonAdmins() throws Exception {
        int before = statusCount();
        connection(new ConnectionAuth("github", false));
        connection(ConnectionAuth.MANUAL);
        signer.currentUrl(1).orElseThrow();
        String secret = secrets.read(ConnectorOAuthSigner.SECRET_NAME).orElseThrow();

        String body = mvc.perform(get(SIGNING))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeAvailable").value(true))
            .andExpect(jsonPath("$.set").value(true))
            .andExpect(jsonPath("$.passageConfigured").value(true))
            .andExpect(jsonPath("$.connectionCount").value(before + 1))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);

        signIn(UserRole.MEMBER);
        mvc.perform(get(SIGNING)).andExpect(status().isForbidden());
        mvc.perform(post(SIGNING + "/regenerate")).andExpect(status().isForbidden());
        assertThat(secrets.read(ConnectorOAuthSigner.SECRET_NAME)).contains(secret);
        verify(oauth, never()).replaceTokenUrl(anyString(), anyString());
    }


    /** 다른 검사가 남긴 연결까지 센 지금의 단추 연결 수다. */
    private int statusCount() {
        return (int)connections.findAll().stream()
            .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED
                && connection.fields().auth().usesOAuth())
            .count();
    }


    /** 연결은 사용자와 플러그인마다 하나라 연결마다 주인을 따로 만든다. */
    private ConnectorConnection connection(ConnectionAuth auth) {
        ConnectorConnection connection = ConnectorConnection.pending(owner().id(), CONNECTOR, clock.instant());
        connection.connected(new ConnectionFields(Map.of(), Map.of(), auth), clock.instant());
        return connections.save(connection);
    }


    private AppUser owner() {
        return users.save(AppUser.of("oauth-signing-" + UUID.randomUUID() + "@example.test", "검사 사용자", admin.groupId(), UserRole.MEMBER));
    }


    private AppUser signIn(UserRole role) {
        AppUser user = users.save(AppUser.of("oauth-signing-" + UUID.randomUUID() + "@example.test", "검사 사용자", 1L, role));
        CurrentUser principal = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return user;
    }


    private static ConnectorManifest manifest() {
        List<ConnectorField> fields = List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            CONNECTOR, "데모 노트", "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null,
            new ConnectorOAuthDeclaration("github", AppScope.PLUGIN, List.of("token"), "https://accounts.example.test/settings", null));
    }
}
