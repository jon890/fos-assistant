package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.config.ArtifactProperties;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.workspace.config.WorkspaceProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 {@code file_space_put} 이 요청자의 「내 파일」 에 넣고, 실패를 숨기지 않는 것을 확인한다
 * (스펙 workspace REQ1, REQ2, REQ4, REQ7, REQ8).
 *
 * <p>실행 공간 루트는 임시 디렉터리로 바꾼다. 도구 호출은 이 검사의 profile 로 도는 아빠의 실행 루트로 서명한다(ADR-032).
 */
@BackendIntegrationTest
@OverrideProperties("assistant.sandbox-workspace.root=build/test-file-space-sandbox-${org.gradle.test.worker:0}")
class McpFileSpacePutToolTest {
    private static final String PROFILE = "mcp-file-space-put";
    private static final String NOT_SAID = "사용자에게 넣었다고 말하지 않는다";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRows;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ArtifactStore store;

    @Autowired
    ArtifactProperties artifactProperties;

    @Autowired
    ChatArtifactRepository rows;

    @Autowired
    ArtifactVaultService vault;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    LiveProperties<WorkspaceProperties> workspace;

    @Autowired
    DataKeyService dataKeys;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser mom;
    private Agent momsAgent;
    private Conversation conversation;
    private String token;
    private String root;
    private AgentExecution run;
    private Path sandbox;


    @BeforeEach
    void setUp() throws Exception {
        deleteTree(Path.of(artifactProperties.root()).toAbsolutePath());
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        tokenRows.deleteAll();
        dad = users.save(AppUser.of("put-dad-" + UUID.randomUUID() + "@example.com", "아빠", 1L, UserRole.ADMIN));
        mom = users.save(AppUser.of("put-mom-" + UUID.randomUUID() + "@example.com", "엄마", 1L, UserRole.MEMBER));
        // 엄마가 만들어 그룹에 공개한 에이전트를 아빠가 쓴다. 실행 공간은 엄마의 것이다.
        // 검사 클래스들이 H2 하나를 함께 써서 앞 검사가 만든 에이전트가 남는다. 남았으면 그 주인을 엄마로 쓴다.
        momsAgent = agents.findAll().stream()
            .filter(agent -> PROFILE.equals(agent.hermesProfile()))
            .findFirst()
            .orElseGet(() -> agents.save(Agent.of(
                PROFILE, "엄마의 비서", PROFILE, "http://runtime.test/p/" + PROFILE, CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, mom.id())));
        mom = users.findById(momsAgent.ownerUserId()).orElse(mom);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "가계부", momsAgent.id(), Instant.now()));
        token = tokens.issue(PROFILE, "put").rawToken();
        root = McpCallSigner.newRoot();
        run = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .agentId(momsAgent.id())
            .conversationId(conversation.id())
            .profileName(PROFILE)
            .hermesSessionId(root)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.parse("2026-11-19T00:00:00Z"))
            .build());
        Path workspaceRoot = Path.of(workspace.current().root()).toAbsolutePath();
        deleteTree(workspaceRoot);
        sandbox = Files.createDirectories(workspaceRoot.resolve(momsAgent.sandboxOwner()));
    }


    @AfterEach
    void tearDown() throws Exception {
        rows.deleteAll(rows.findAll().stream().filter(row -> row.conversationId().equals(conversation.id())).toList());
        deleteTree(Path.of(workspace.current().root()).toAbsolutePath());
        dataKeys.forgetCachedKeys();
    }


    @Test
    @DisplayName("작업 폴더의 엑셀을 요청자의 내 파일에 넣고 같은 바이트로 읽힌다. 에이전트 주인의 목록에는 없다")
    void putsWorkspaceFileIntoRequesterVault() throws Exception {
        byte[] xlsx = xlsx(1);
        Files.createDirectories(sandbox.resolve("reports"));
        Files.write(sandbox.resolve("reports/가계부.xlsx"), xlsx);

        JsonNode result = put("/workspace/reports/가계부.xlsx", "11월 가계부", "식비와 교통비 정리");

        assertThat(isError(result)).isFalse();
        JsonNode output = json.readTree(text(result));
        assertThat(output.path("saved").asBoolean()).isTrue();
        assertThat(output.path("already").asBoolean()).isFalse();
        assertThat(output.path("title").asString()).isEqualTo("11월 가계부");
        List<ArtifactVaultItem> kept = vault.list(dad.id());
        assertThat(kept).extracting(ArtifactVaultItem::path).contains("reports/가계부.xlsx");
        ArtifactVaultItem item = kept.stream().filter(it -> it.path().equals("reports/가계부.xlsx")).findFirst().orElseThrow();
        assertThat(vault.read(dad.id(), item.id()).body()).isEqualTo(xlsx);
        assertThat(vault.list(mom.id())).as("에이전트 주인의 보관함에는 없다").isEmpty();
        assertThatThrownBy(() -> vault.read(mom.id(), item.id()))
            .as("다른 사용자는 없는 파일과 같은 응답을 받는다")
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ARTIFACT_NOT_FOUND));
    }


    @Test
    @DisplayName("대화 폴더의 파일은 상대 경로와 폴더의 절대 경로로 모두 넣는다")
    void putsConversationFolderFileByRelativeAndAbsolutePath() throws Exception {
        store.write(conversation.id(), "page/index.html", "<h1>보고서</h1>".getBytes(StandardCharsets.UTF_8));
        Path folder = store.ensureFolder(conversation.id());
        Files.write(folder.resolve("q3.xlsx"), xlsx(2));

        JsonNode relative = put("q3.xlsx", "3분기 표", null);
        JsonNode absolute = put(store.agentFolder(conversation.id()) + "/page/index.html", "보고서", null);

        assertThat(isError(relative)).as(text(relative)).isFalse();
        assertThat(isError(absolute)).as(text(absolute)).isFalse();
        assertThat(vault.list(dad.id())).extracting(ArtifactVaultItem::path).contains("q3.xlsx", "page/index.html");
    }


    @Test
    @DisplayName("다른 대화의 폴더와 폴더 밖 경로, 위로 나가는 경로, 링크는 찾지 못했다고 답한다")
    void refusesPathsOutsideAsNotFound() throws Exception {
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "다른 대화", momsAgent.id(), Instant.now()));
        Path otherFolder = store.ensureFolder(other.id());
        Files.write(otherFolder.resolve("secret.xlsx"), xlsx(3));
        Files.write(sandbox.resolve("real.csv"), "a,b\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(sandbox.resolve("link.csv"), sandbox.resolve("real.csv"));

        for (String path : List.of(
            store.agentFolder(other.id()) + "/secret.xlsx", "/etc/hosts.csv", "../" + other.id() + "/secret.xlsx",
            "/workspace/../u1/a.csv", "/workspace/link.csv", "/workspace/none.csv")) {
            JsonNode result = put(path, null, null);
            assertThat(isError(result)).as(path).isTrue();
            assertThat(text(result)).as(path).startsWith("파일 공간에 넣지 못했다").contains("NOT_FOUND", NOT_SAID)
                .doesNotContain(artifactProperties.root(), sandbox.toString(), "secret");
        }
        assertThat(vault.list(dad.id())).isEmpty();
        rows.deleteAll(rows.findAll().stream().filter(row -> row.conversationId().equals(other.id())).toList());
    }


    @Test
    @DisplayName("받지 않는 형식과 상한 초과와 용량 초과는 까닭을 알리고 내 파일에 생기지 않는다")
    void refusesUnsupportedLargeAndFullWithReason() throws Exception {
        Files.write(sandbox.resolve("bundle.zip"), new byte[] {'P', 'K', 3, 4});
        Files.write(sandbox.resolve("big.csv"), new byte[25 * 1024 * 1024 + 1]);
        Files.write(sandbox.resolve("fake.xlsx"), "not a zip".getBytes(StandardCharsets.UTF_8));
        Files.write(sandbox.resolve("ok.csv"), "a,b\n".getBytes(StandardCharsets.UTF_8));

        assertReason(put("/workspace/bundle.zip", null, null), "UNSUPPORTED_TYPE");
        assertReason(put("/workspace/fake.xlsx", null, null), "UNSUPPORTED_TYPE");
        assertReason(put("/workspace/big.csv", null, null), "TOO_LARGE");
        assertThat(vault.list(dad.id())).isEmpty();
        fillQuota(dad.id());
        assertReason(put("/workspace/ok.csv", null, null), "QUOTA_FULL");
        assertThat(vault.list(dad.id())).extracting(ArtifactVaultItem::path).doesNotContain("ok.csv");
    }


    @Test
    @DisplayName("같은 파일을 두 번 넣으면 한 건이고 둘째는 이미 있다고 답한다")
    void secondPutOfSameFileIsAlready() throws Exception {
        Files.write(sandbox.resolve("same.csv"), "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));

        JsonNode first = put("/workspace/same.csv", "같은 표", null);
        JsonNode second = put("/workspace/same.csv", "같은 표", null);

        assertThat(json.readTree(text(first)).path("already").asBoolean()).isFalse();
        assertThat(json.readTree(text(second)).path("already").asBoolean()).isTrue();
        assertThat(vault.list(dad.id())).hasSize(1);
    }


    @Test
    @DisplayName("모르는 인자와 비어 있는 경로는 인자 오류다")
    void rejectsUnknownArguments() throws Exception {
        ObjectNode unknown = json.createObjectNode().put("path", "a.csv").put("conversation_id", UUID.randomUUID().toString());
        ObjectNode blank = json.createObjectNode().put("path", " ");

        assertThat(call(unknown).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(call(blank).path("error").path("code").asInt()).isEqualTo(-32602);
    }


    @Test
    @DisplayName("먼저 살펴보기에서는 쓰기를 허용한 점검의 대화에서만 넣는다")
    void checkTreeAllowsOnlyWritesAllowedCheck() throws Exception {
        Files.write(sandbox.resolve("check.csv"), "a,b\n".getBytes(StandardCharsets.UTF_8));
        ProactiveCheck refused = ProactiveCheck.started(dad.id(), momsAgent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        refused.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(refused);
        try {
            JsonNode result = put("/workspace/check.csv", null, null);
            assertThat(isError(result)).isTrue();
            assertThat(vault.list(dad.id())).isEmpty();
        } finally {
            checks.delete(saved);
        }

        ProactiveCheck allowed = ProactiveCheck.started(dad.id(), momsAgent.id(), conversation.id(), CheckTrigger.MANUAL, true, Instant.now());
        allowed.attachRoot(run.id(), root);
        ProactiveCheck savedAllowed = checks.save(allowed);
        try {
            JsonNode result = put("/workspace/check.csv", null, null);
            assertThat(isError(result)).as(text(result)).isFalse();
        } finally {
            checks.delete(savedAllowed);
        }
    }


    private void assertReason(JsonNode result, String reason) {
        assertThat(isError(result)).as(reason).isTrue();
        assertThat(text(result)).startsWith("파일 공간에 넣지 못했다").contains(reason, NOT_SAID)
            .doesNotContain(sandbox.toString());
    }


    /** 보관함 용량을 채운 행 하나를 넣는다. 용량은 지우지 않은 보관 행의 바이트 합으로 센다. */
    private void fillQuota(Long userId) {
        jdbc.update(
            "INSERT INTO chat_artifact (conversation_id, path, byte_size, created_at, vault_user_id, vault_fingerprint,"
                + " auto_vault_attempts) VALUES (?, ?, ?, ?, ?, ?, 0)",
            conversation.id(), "filler.bin", 5L * 1024 * 1024 * 1024, Timestamp.from(Instant.now()), userId,
            UUID.randomUUID().toString().replace("-", ""));
    }


    private JsonNode put(String path, String title, String description) throws Exception {
        ObjectNode arguments = json.createObjectNode().put("path", path);
        if (title != null) {
            arguments.put("title", title);
        }
        if (description != null) {
            arguments.put("description", description);
        }
        return call(arguments);
    }


    private JsonNode call(ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", "file_space_put").set("arguments", arguments);
        String signed = McpCallSigner.withContext(request.toString(), token, root);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(signed))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        if (body.has("result")) {
            McpToolResultShape.assertCallToolResult("file_space_put", body.path("result"));
        }
        return body;
    }


    private static boolean isError(JsonNode result) {
        return result.path("result").path("isError").asBoolean();
    }


    private static String text(JsonNode result) {
        return result.path("result").path("content").get(0).path("text").asString();
    }


    /** zip 머리를 가진 엑셀 표본이다. 보관함은 머리 바이트만 본다. */
    private static byte[] xlsx(int filler) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte)filler);
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        return bytes;
    }


    private static void deleteTree(Path target) throws Exception {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
