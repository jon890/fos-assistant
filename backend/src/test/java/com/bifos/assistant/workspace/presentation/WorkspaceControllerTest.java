package com.bifos.assistant.workspace.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.shared.util.SandboxedContentPolicy;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.WorkspaceCursor;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * 파일 공간 경로가 HTTP 경계에서 돌려주는 상태 코드와 머리글을 본다. 계약은 {@code backend/docs/code-architecture.md} 의 「실행 공간 파일」 이다.
 *
 * <p>요청자는 인증 필터가 채우는 것과 같은 보안 문맥으로 넣는다. 실행 공간 루트는 임시 디렉터리다.
 */
@BackendIntegrationTest
class WorkspaceControllerTest {

    private static final CurrentUser MEMBER = new CurrentUser(301L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path root;

    @Autowired
    AgentService agentService;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> createdAgents = new ArrayList<>();

    @Test
    @DisplayName("목록 HTTP는 마지막 표시 키의 cursor와 다음 페이지 여부를 함께 돌려준다")
    void returnsCursorAndCompleteLastPage() throws Exception {
        Path owner = Files.createDirectory(root.resolve("u301"));
        for (int i = 1_001; i >= 0; i--) {
            Files.createFile(owner.resolve(String.format("f%04d", i)));
        }
        MockMvc mvc = mvc(root.toString());
        String body = mvc.perform(get("/api/v1/workspace/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1_000))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor =
                JsonMapper.builder().build().readTree(body).get("nextCursor").asString();
        mvc.perform(get("/api/v1/workspace/entries").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].name", contains("f1000", "f1001")))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("잘못된 cursor는 400이고 미인증과 설정 해제와 주인 교체도 기존 거절을 유지한다")
    void rejectsInvalidCursorAndRechecksOwner() throws Exception {
        write("a", "own");
        String cursor = WorkspaceCursor.encode(
                WorkspacePath.parse(""),
                new WorkspaceEntry("a", WorkspaceEntryKind.FILE, 0L, Instant.EPOCH, true, true));
        MockMvc mvc = mvc(root.toString());
        mvc.perform(get("/api/v1/workspace/entries").param("cursor", "!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/workspace/entries").param("path", "other").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc("").perform(get("/api/v1/workspace/entries").param("cursor", cursor))
                .andExpect(status().isServiceUnavailable());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/workspace/entries").param("cursor", cursor)).andExpect(status().isUnauthorized());
        setUp();
        Path owner = root.resolve("u301");
        Files.move(owner, root.resolve("old"));
        Files.createSymbolicLink(owner, root.resolve("old"));
        mvc.perform(get("/api/v1/workspace/entries").param("cursor", cursor)).andExpect(status().isNotFound());
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(MEMBER, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        createdAgents.forEach(code -> jdbc.update("DELETE FROM agent WHERE code = ?", code));
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("루트가 비면 쓸 수 없고, 사용자 디렉터리가 생기면 있다고 답한다")
    void reportsAvailabilityAndExistence() throws Exception {
        mvc("").perform(get("/api/v1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.deletable").value(false));

        MockMvc mvc = mvc(root.toString());
        mvc.perform(get("/api/v1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.deletable").value(false))
                .andExpect(jsonPath("$.runningExecutions").value(0));
        mvc.perform(get("/api/v1/workspace/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", empty()));

        Files.createDirectory(root.resolve("u301"));
        mvc.perform(get("/api/v1/workspace")).andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    @DisplayName("요청자가 주인인 에이전트만 보이고 그룹에 공개한 것만 shared 다")
    void listsOwnAgentsWithSharedFlag() throws Exception {
        String own = agent(AgentVisibility.PRIVATE, MEMBER.id());
        String shared = agent(AgentVisibility.GROUP, MEMBER.id());
        String others = agent(AgentVisibility.GROUP, 302L);

        mvc(root.toString())
                .perform(get("/api/v1/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[?(@.code == '" + own + "')].shared", contains(false)))
                .andExpect(jsonPath("$.agents[?(@.code == '" + shared + "')].shared", contains(true)))
                .andExpect(jsonPath("$.agents[?(@.code == '" + others + "')]", empty()));
    }

    @Test
    @DisplayName("남의 공간에만 있는 경로는 404 이고 상위 조각은 400 이다")
    void hidesOtherUsersWorkspace() throws Exception {
        Files.createDirectory(root.resolve("u301"));
        Path sibling = Files.createDirectories(root.resolve("u302/private"));
        Files.writeString(sibling.resolve("secret.txt"), "sibling secret");
        MockMvc mvc = mvc(root.toString());

        mvc.perform(get("/api/v1/workspace/entries").param("path", "private"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND.name()));
        mvc.perform(get("/api/v1/workspace/files/private/secret.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND.name()));
        mvc.perform(get("/api/v1/workspace/entries").param("path", "../u302"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("HTML 미리보기는 결과물과 같은 CSP 와 격리 머리글을 붙인다")
    void sendsHtmlPreviewHeaders() throws Exception {
        write("a.html", "<p>hi</p>");

        mvc(root.toString())
                .perform(get("/api/v1/workspace/files/a.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("text/html; charset=utf-8")))
                .andExpect(header().string("Content-Security-Policy", SandboxedContentPolicy.HTML))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline")))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 9L))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andExpect(content().string("<p>hi</p>"));
    }

    @Test
    @DisplayName("글과 CSS 와 SVG 미리보기는 스크립트를 막는 CSP 로 준다")
    void sendsTextPreviewsWithoutScripts() throws Exception {
        write("a.txt", "text");
        write("a.css", "p{}");
        write("a.svg", "<svg/>");
        MockMvc mvc = mvc(root.toString());

        mvc.perform(get("/api/v1/workspace/files/a.txt"))
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain; charset=utf-8")))
                .andExpect(header().string("Content-Security-Policy", SandboxedContentPolicy.NONE));
        mvc.perform(get("/api/v1/workspace/files/a.css"))
                .andExpect(content().contentType(MediaType.parseMediaType("text/css; charset=utf-8")))
                .andExpect(header().string("Content-Security-Policy", SandboxedContentPolicy.NONE));
        mvc.perform(get("/api/v1/workspace/files/a.svg"))
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain; charset=utf-8")))
                .andExpect(header().string("Content-Security-Policy", SandboxedContentPolicy.NONE));
    }

    @Test
    @DisplayName("내려받기는 형식을 보지 않고 attachment 와 UTF-8 이름으로 준다")
    void sendsDownloadAsAttachment() throws Exception {
        write("a.bin", "binary");

        mvc(root.toString())
                .perform(get("/api/v1/workspace/files/a.bin").param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=UTF-8''a.bin")))
                .andExpect(header().string("Content-Security-Policy", SandboxedContentPolicy.NONE))
                .andExpect(content().string("binary"));
    }

    @Test
    @DisplayName("한글 이름을 조각 인코딩한 주소로 연다")
    void opensEncodedKoreanName() throws Exception {
        write("보고서.csv", "a,b");

        mvc(root.toString())
                .perform(get("/api/v1/workspace/files/{name}", "보고서.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=UTF-8''")))
                .andExpect(content().string("a,b"));
    }

    @Test
    @DisplayName("1 MiB 를 넘는 글 미리보기는 413 이고 내려받기는 200 이다")
    void rejectsLargePreviewButDownloads() throws Exception {
        write("big.txt", "a".repeat((int) MIB + 1));
        MockMvc mvc = mvc(root.toString());

        mvc.perform(get("/api/v1/workspace/files/big.txt"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_PREVIEW_TOO_LARGE.name()));
        mvc.perform(get("/api/v1/workspace/files/big.txt").param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, MIB + 1));
    }

    @Test
    @DisplayName("미리보기를 정하지 않은 확장자는 415 다")
    void rejectsUnsupportedPreview() throws Exception {
        write("a.bin", "binary");

        mvc(root.toString())
                .perform(get("/api/v1/workspace/files/a.bin"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_PREVIEW_UNSUPPORTED.name()));
    }

    @Test
    @DisplayName("루트가 비면 목록은 503 이다")
    void rejectsListingWhenUnavailable() throws Exception {
        mvc("").perform(get("/api/v1/workspace/entries"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.WORKSPACE_UNAVAILABLE.name()));
    }

    private MockMvc mvc(String rootValue) {
        WorkspaceService service = new WorkspaceService(
                LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(rootValue, "")),
                agentService,
                limiter,
                (owner, path, maxEntries) -> {
                    throw new AssertionError("이 시험은 지우지 않는다");
                });
        return MockMvcBuilders.standaloneSetup(new WorkspaceController(service, new CurrentUserProvider()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void write(String name, String content) throws IOException {
        Path owner = Files.createDirectories(root.resolve("u301"));
        Files.writeString(owner.resolve(name), content);
    }

    private String agent(AgentVisibility visibility, Long ownerUserId) {
        String code = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        agentRepository.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                ownerUserId,
                Instant.parse("2026-10-09T00:00:00Z")));
        createdAgents.add(code);
        return code;
    }
}
