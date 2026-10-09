package com.bifos.assistant.workspace.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.WorkspaceDeleter;
import com.bifos.assistant.workspace.domain.WorkspaceDeletion;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 지우기 경로가 주인과 경로를 요청자로 정하고, 도우미를 부르기 전에 거절할 것을 거절하는지 본다. 계약은
 * {@code docs/code-architecture.md} 의 「지우기」 다.
 *
 * <p>도우미는 받은 인자를 기록하는 가짜다. 실행 공간 루트는 임시 디렉터리다.
 */
@BackendIntegrationTest
class WorkspaceControllerDeleteTest {

    private static final CurrentUser MEMBER = new CurrentUser(301L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final String SOCKET = "/helper.sock";

    @TempDir
    Path root;

    @Autowired
    AgentService agentService;

    @Autowired
    UserExecutionLimiter limiter;

    private final List<String> calls = new ArrayList<>();
    private RuntimeException helperFailure;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(MEMBER, null, List.of()));
        Files.createDirectory(root.resolve("u301"));
        Path sibling = Files.createDirectories(root.resolve("u302"));
        Files.writeString(sibling.resolve("b.txt"), "sibling");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("요청자의 파일을 지우면 도우미가 받은 주인은 요청자이고 경로는 요청한 경로다")
    void deletesOwnEntryAsRequester() throws Exception {
        Files.writeString(root.resolve("u301/a.txt"), "mine");

        mvc(SOCKET)
                .perform(delete("/api/v1/workspace/entries").param("path", "a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.entries").value(1))
                .andExpect(jsonPath("$.bytes").value(4));

        assertThat(calls).containsExactly("u301 a.txt 10000");
    }

    @Test
    @DisplayName("상위 조각은 400 이고 남의 공간에만 있는 경로는 404 이며 도우미를 부르지 않는다")
    void rejectsOtherUsersPathWithoutCallingHelper() throws Exception {
        MockMvc mvc = mvc(SOCKET);

        mvc.perform(delete("/api/v1/workspace/entries").param("path", "../u302/b.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
        mvc.perform(delete("/api/v1/workspace/entries").param("path", "b.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND.name()));

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("빈 경로는 400 이고 링크를 지나는 경로는 404 이며 도우미를 부르지 않는다")
    void rejectsEmptyAndLinkedPathWithoutCallingHelper() throws Exception {
        Files.createSymbolicLink(root.resolve("u301/peek"), root.resolve("u302"));
        MockMvc mvc = mvc(SOCKET);

        mvc.perform(delete("/api/v1/workspace/entries").param("path", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
        mvc.perform(delete("/api/v1/workspace/entries"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
        mvc.perform(delete("/api/v1/workspace/entries").param("path", "peek/b.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND.name()));

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("도우미가 항목이 너무 많다고 답하면 409 다")
    void passesHelperRejection() throws Exception {
        Files.createDirectory(root.resolve("u301/many"));
        helperFailure = new ApiException(ErrorCode.WORKSPACE_DELETE_TOO_MANY, "too many");

        mvc(SOCKET)
                .perform(delete("/api/v1/workspace/entries").param("path", "many"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_DELETE_TOO_MANY.name()));

        assertThat(calls).containsExactly("u301 many 10000");
    }

    @Test
    @DisplayName("socket 이 비면 지우기는 503 이고 상태의 deletable 이 거짓이다")
    void rejectsWhenSocketIsNotConfigured() throws Exception {
        Files.writeString(root.resolve("u301/a.txt"), "mine");
        MockMvc mvc = mvc("");

        mvc.perform(delete("/api/v1/workspace/entries").param("path", "a.txt"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_DELETE_UNAVAILABLE.name()));
        mvc.perform(get("/api/v1/workspace")).andExpect(jsonPath("$.deletable").value(false));

        assertThat(calls).isEmpty();
    }

    private MockMvc mvc(String socket) {
        WorkspaceDeleter deleter = (owner, path, maxEntries) -> {
            calls.add(owner + " " + path.value() + " " + maxEntries);
            if (helperFailure != null) {
                throw helperFailure;
            }
            return new WorkspaceDeletion(WorkspaceEntryKind.FILE, 1, 4);
        };
        WorkspaceService service = new WorkspaceService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(root.toString(), socket)),
                agentService,
                limiter,
                deleter);
        return MockMvcBuilders.standaloneSetup(new WorkspaceController(service, new CurrentUserProvider()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
