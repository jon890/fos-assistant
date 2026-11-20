package com.bifos.assistant.admin.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.secret.presentation.ConnectorOAuthAppAdminController;
import com.bifos.assistant.connector.connection.presentation.ConnectorConnectionController;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@BackendIntegrationTest
class ConnectorOAuthAppAdminControllerTest {
    private static final String APPS = "/api/v1/admin/secrets/connector-oauth/apps";
    private static final String SETTINGS = "https://accounts.example.test/settings";
    private static final String CLIENT_SECRET = "client-secret-for-test-123";

    @Autowired
    ConnectorOAuthAppAdminController controller;

    @Autowired
    ConnectorConnectionController connections;

    @Autowired
    HermesConnectorClient hermes;

    @Autowired
    ServerSecrets secrets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    private MockMvc mvc;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller, connections).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(hermes.readCatalog()).thenReturn(List.of(
            manifest("demo-notes", "github", AppScope.PLUGIN),
            manifest("demo-tasks", "github", AppScope.PLUGIN),
            manifest("demo-mail", "google", AppScope.SHARED),
            manifest("demo-calendar", "google", AppScope.SHARED)));
    }


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("비관리자 요청은 거절하고 목록에 없는 앱은 만들지 않는다")
    void rejectsNonAdminAndUndeclaredApps() throws Exception {
        signIn(UserRole.MEMBER);
        mvc.perform(get(APPS)).andExpect(status().isForbidden());
        mvc.perform(save("demo-notes", "demo-app")).andExpect(status().isForbidden());
        mvc.perform(delete(APPS + "/demo-notes")).andExpect(status().isForbidden());

        signIn(UserRole.ADMIN);
        mvc.perform(save("unknown", "demo-app")).andExpect(status().isNotFound());
        mvc.perform(save("demo-notes", null)).andExpect(status().isBadRequest());
        assertThat(secrets.find("oauth_app:github:demo-notes")).isEmpty();
        assertThat(secrets.find("oauth_app:github:unknown")).isEmpty();
    }


    @Test
    @DisplayName("카탈로그에서 빠진 플러그인의 기존 앱은 비울 수 있다")
    void clearsStoredAppAfterPluginIsRemoved() throws Exception {
        signIn(UserRole.ADMIN);
        when(hermes.readCatalog()).thenReturn(List.of(manifest("demo-obsolete", "github", AppScope.PLUGIN)));
        mvc.perform(save("demo-obsolete", "demo-app")).andExpect(status().isOk());

        when(hermes.readCatalog()).thenReturn(List.of());
        mvc.perform(save("demo-obsolete", "demo-app")).andExpect(status().isNotFound());
        mvc.perform(delete(APPS + "/demo-obsolete")).andExpect(status().isNoContent());
        mvc.perform(delete(APPS + "/demo-missing")).andExpect(status().isNotFound());

        assertThat(secrets.find("oauth_app:github:demo-obsolete")).hasValueSatisfying(row -> {
            assertThat(row.set()).isFalse();
            assertThat(row.meta()).isEmpty();
        });
        assertThat(secrets.find("oauth_app:github:demo-missing")).isEmpty();
    }


    @Test
    @DisplayName("앱은 선언 범위대로 저장하고 관리자 응답에는 비밀 앞부분만 보인다")
    void storesEncryptedAppCredentialsAndUsesDeclaredScope() throws Exception {
        signIn(UserRole.ADMIN);
        mvc.perform(get(APPS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeAvailable").value(true))
            .andExpect(jsonPath("$.apps.length()").value(3))
            .andExpect(jsonPath("$.apps[0].set").value(false));
        String saved = mvc.perform(save("demo-notes", "demo-app"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.set").value(true))
            .andExpect(jsonPath("$.prefix").value("clie"))
            .andExpect(jsonPath("$.clientId").value("example-client"))
            .andExpect(jsonPath("$.appSlug").value("demo-app"))
            .andExpect(jsonPath("$.updatedBy.name").value("검사 사용자"))
            .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain(CLIENT_SECRET);

        mvc.perform(save("demo-calendar", null)).andExpect(status().isOk());
        assertThat(secrets.read("oauth_app:github:demo-notes")).contains(CLIENT_SECRET);
        assertThat(secrets.read("oauth_app:google")).contains(CLIENT_SECRET);
        assertThat(secrets.find("oauth_app:github:demo-tasks")).isEmpty();
        String ciphertext = jdbc.queryForObject(
            "select sealed_value from server_secret where name = ?", String.class, "oauth_app:github:demo-notes");
        assertThat(ciphertext).startsWith("v1.").doesNotContain(CLIENT_SECRET);

        String listed = mvc.perform(get(APPS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apps[1].set").value(false))
            .andExpect(jsonPath("$.apps[2].connectorId").value("demo-mail"))
            .andExpect(jsonPath("$.apps[2].set").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(CLIENT_SECRET);

        mvc.perform(delete(APPS + "/demo-mail")).andExpect(status().isNoContent());
        assertThat(secrets.read("oauth_app:google")).isEmpty();
        assertThat(secrets.find("oauth_app:google").orElseThrow().meta()).isEmpty();
        assertThat(secrets.read("oauth_app:github:demo-notes")).contains(CLIENT_SECRET);

        signIn(UserRole.MEMBER);
        mvc.perform(get("/api/v1/connectors"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].oauth.ready").value(false))
            .andExpect(jsonPath("$[0].fields[0].key").value("token"));
    }


    private void signIn(UserRole role) {
        AppUser user = users.save(AppUser.of("oauth-" + UUID.randomUUID() + "@example.test", "검사 사용자", 1L, role));
        CurrentUser principal = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }


    private static MockHttpServletRequestBuilder save(String connector, String slug) {
        String slugValue = slug == null ? "null" : "\"" + slug + "\"";
        return put(APPS + "/" + connector).contentType(MediaType.APPLICATION_JSON).content("""
            {"clientId":"example-client","clientSecret":"%s","appSlug":%s}
            """.formatted(CLIENT_SECRET, slugValue));
    }


    private static ConnectorManifest manifest(String id, String provider, AppScope scope) {
        List<ConnectorField> fields = List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null));
        return new ConnectorManifest(
            id, id, "", fields, "verify", "demo", 1, List.of(), List.of(),
            ConnectorAppearance.NONE, false, false, null, null, null, null, null, null, null,
            new ConnectorOAuthDeclaration(provider, scope, List.of("token"), SETTINGS, null));
    }
}
