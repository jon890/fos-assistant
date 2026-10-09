package com.bifos.assistant.workspace.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.workspace.application.WorkspaceUsageService;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 관리자 용량 경로의 권한과 응답 모양을 HTTP 경계에서 본다. 계약은 {@code backend/docs/code-architecture.md} 의 「관리자 용량」 이다. */
@BackendIntegrationTest
class WorkspaceAdminControllerTest {

    private static final CurrentUser MEMBER = new CurrentUser(301L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(302L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);

    @TempDir
    Path root;

    @Autowired
    UserDisplayNameService userNames;

    @Autowired
    AgentService agents;

    @Autowired
    Clock clock;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("MEMBER 역할은 403 이다")
    void forbidsMember() throws Exception {
        signIn(MEMBER);

        mvc(root.toString()).perform(get("/api/v1/admin/workspaces")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 공간마다 용량을 받고 파일 이름은 응답에 없다")
    void letsAdminReadUsageWithoutFileNames() throws Exception {
        Path owner = Files.createDirectories(root.resolve("u301"));
        Files.write(owner.resolve("secret-plan.txt"), new byte[42]);
        signIn(ADMIN);

        mvc(root.toString())
                .perform(get("/api/v1/admin/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.spaces.length()").value(1))
                .andExpect(jsonPath("$.spaces[0].kind").value("USER"))
                .andExpect(jsonPath("$.spaces[0].id").value(301))
                .andExpect(jsonPath("$.spaces[0].bytes").value(42))
                .andExpect(jsonPath("$.spaces[0].entries").value(1))
                .andExpect(jsonPath("$.spaces[0].partial").value(false))
                .andExpect(content().string(not(containsString("secret-plan"))));
    }

    @Test
    @DisplayName("루트가 비면 관리자에게도 200 과 쓸 수 없음을 준다")
    void reportsUnavailableToAdmin() throws Exception {
        signIn(ADMIN);

        mvc("").perform(get("/api/v1/admin/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.spaces", empty()));
    }

    private MockMvc mvc(String rootValue) {
        WorkspaceUsageService service = new WorkspaceUsageService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(rootValue, "")),
                userNames,
                agents,
                clock);
        return MockMvcBuilders.standaloneSetup(new WorkspaceAdminController(service, new CurrentUserProvider()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
