package com.bifos.assistant.admin.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.secret.presentation.ServerSecretAdminController;
import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.crypto.domain.ServerSecrets;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 관리자 「서버 비밀값」 경로의 권한과 응답 모양, 원문이 응답에 없는지를 HTTP 경계에서 본다(서버 비밀값 스펙 REQ1-REQ3, REQ18). */
@BackendIntegrationTest
class ServerSecretAdminControllerTest {

    @Autowired
    ServerSecretAdminController controller;

    @Autowired
    ServerSecrets secrets;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    AppUserRepository users;

    private MockMvc mvc;
    private CurrentUser admin;
    private CurrentUser member;


    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        admin = user(UserRole.ADMIN, "관리자");
        member = user(UserRole.MEMBER, "아이");
        when(connector.readCatalog()).thenReturn(List.of());
    }


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("MEMBER 역할은 세 경로 모두 403 이고 값이 생기지 않는다")
    void forbidsMember() throws Exception {
        signIn(member);

        mvc.perform(get("/api/v1/admin/secrets")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/secrets/browser-gateway/regenerate")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/secrets/browser-gateway")).andExpect(status().isForbidden());

        assertThat(secrets.exists(BrowserGatewayTokens.SECRET_NAME)).isFalse();
    }


    @Test
    @DisplayName("관리자가 새로 만들면 입력됨과 앞부분과 이름이 보이고 어느 응답에도 원문이 없다")
    void regeneratesWithoutLeakingValue() throws Exception {
        signIn(admin);
        mvc.perform(get("/api/v1/admin/secrets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storeAvailable").value(true))
            .andExpect(jsonPath("$.browserGateway.set").value(false))
            .andExpect(jsonPath("$.browserGateway.bindingCount").value(0));

        String regenerated = mvc.perform(post("/api/v1/admin/secrets/browser-gateway/regenerate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.browserGateway.set").value(true))
            .andExpect(jsonPath("$.browserGateway.updatedBy.name").value("관리자"))
            .andExpect(jsonPath("$.reinstall.total").value(0))
            .andExpect(jsonPath("$.reinstall.catalogUnavailable").value(false))
            .andReturn().getResponse().getContentAsString();
        String value = secrets.read(BrowserGatewayTokens.SECRET_NAME).orElseThrow();
        String overview = mvc.perform(get("/api/v1/admin/secrets"))
            .andExpect(jsonPath("$.browserGateway.prefix").value(value.substring(0, 4)))
            .andReturn().getResponse().getContentAsString();

        assertThat(regenerated).doesNotContain(value);
        assertThat(overview).doesNotContain(value);
    }


    @Test
    @DisplayName("지우면 비어 있음이 되고 플러그인 목록을 읽지 못해도 화면은 열리며 바인딩 수만 비어 있다")
    void deletesAndSurvivesUnavailableCatalog() throws Exception {
        signIn(admin);
        mvc.perform(post("/api/v1/admin/secrets/browser-gateway/regenerate")).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/secrets/browser-gateway")).andExpect(status().isNoContent());
        when(connector.readCatalog()).thenThrow(new IllegalStateException("catalog down"));

        mvc.perform(get("/api/v1/admin/secrets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.browserGateway.set").value(false))
            .andExpect(jsonPath("$.browserGateway.bindingCount").doesNotExist());
        mvc.perform(post("/api/v1/admin/secrets/browser-gateway/regenerate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.browserGateway.set").value(true))
            .andExpect(jsonPath("$.reinstall.catalogUnavailable").value(true));
    }


    private CurrentUser user(UserRole role, String name) {
        AppUser saved = users.save(AppUser.of("secret-" + UUID.randomUUID() + "@example.com", name, 1L, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }


    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
