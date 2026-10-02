package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.infra.AgentTokenAuthenticationFilter;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP 경계에서 MCP 인증과 JSON-RPC 계약을 확인한다.
 *
 * <p>토큰은 이 검사의 profile 에 묶이고, 도구 호출은 그 profile 로 도는 아빠의 실행 루트로 서명한다(ADR-032).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpMemoryToolTest {
    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRepository;

    @Autowired
    AppUserRepository users;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    MemoryService memories;

    @Autowired
    BuildProperties buildProperties;

    @Autowired
    WebApplicationContext context;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository agentCollections;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubagentSessionRegistrar registrar;

    @Autowired
    McpToolService toolService;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser kid;
    private String dadToken;
    private String dadRoot;
    private AgentExecution dadRun;
    private static final String PROFILE = "mcp-memory-tool";
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        memoryRepository.deleteAll();
        tokenRepository.deleteAll();
        users.deleteAll();
        dad = users.save(AppUser.of("dad@example.com", "아빠", 1L, UserRole.ADMIN));
        kid = users.save(AppUser.of("kid@example.com", "아이", 1L, UserRole.MEMBER));
        dadToken = tokens.issue(PROFILE, "dad").rawToken();
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, agents, dad.id(), 1L, PROFILE, dadRoot);
    }

    @Test
    @DisplayName("initialize 목록과 알림은 계약한 응답을 낸다")
    void initializeListAndNotificationGiveContractedResponses() throws Exception {
        JsonNode initialized = body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"initialize\"}"));
        assertThat(initialized.path("id").asInt()).isEqualTo(17);
        assertThat(initialized.path("result").path("protocolVersion").asString())
                .isEqualTo("2025-03-26");
        assertThat(initialized
                        .path("result")
                        .path("capabilities")
                        .path("tools")
                        .path("listChanged")
                        .asBoolean())
                .isFalse();
        assertThat(initialized.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("fos-assistant");
        assertThat(initialized.path("result").path("serverInfo").path("version").asString())
                .isEqualTo(buildProperties.getVersion());
        JsonNode listed = body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"tools/list\"}"));
        assertThat(listed.path("id").asInt()).isEqualTo(18);
        assertThat(listed.path("result").path("tools")).hasSize(6);
        assertThat(listed.path("result").path("tools").get(0).path("name").asString())
                .isEqualTo("memory_read");
        assertThat(listed.path("result").path("tools").get(1).path("name").asString())
                .isEqualTo("artifact_write");
        assertThat(listed.path("result").path("tools").get(2).path("name").asString())
                .isEqualTo("agent_list");
        assertThat(listed.path("result").path("tools").get(3).path("name").asString())
                .isEqualTo("agent_delegate");
        assertThat(listed.path("result").path("tools").get(4).path("name").asString())
                .isEqualTo("agent_status");
        assertThat(listed.path("result").path("tools").get(5).path("name").asString())
                .isEqualTo("agent_stop");
        HttpResponse<String> notification =
                mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(notification.statusCode()).isEqualTo(202);
        assertThat(notification.body()).isEmpty();
    }

    @Test
    @DisplayName("부모 실행의 사용자만 정하고 권한 오류는 동일하게 숨긴다")
    void decidesOnlyParentRunUserAndHidesPermissionErrorsAlike() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        Memory hidden = memories.create(current(kid), MemoryScope.USER, "비밀", "아이 본문", false);
        JsonNode own = body(call(dadToken, indexed.id(), 999L));
        assertThat(own.path("result").path("isError").asBoolean()).isFalse();
        assertThat(own.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("아빠 본문");
        JsonNode unauthorized = body(call(dadToken, hidden.id(), null));
        JsonNode missing = body(call(dadToken, 999999L, null));
        assertThat(unauthorized.path("result")).isEqualTo(missing.path("result"));
    }

    @Test
    @DisplayName("에이전트가 받지 않는 collection 과 허용받지 않은 민감 항목은 없는 항목과 같은 응답이다")
    void itemsOutsideTheAgentsCollectionsLookLikeMissingItems() throws Exception {
        Memory career = memories.create(
                current(dad),
                MemoryScope.USER,
                "커리어",
                "커리어 본문",
                "career",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);
        Memory sensitive = memories.create(
                current(dad),
                MemoryScope.USER,
                "신원",
                "민감 본문",
                "core",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.SENSITIVE);
        Memory hidden = memories.create(current(kid), MemoryScope.USER, "비밀", "아이 본문", false);
        Memory core = memories.create(current(dad), MemoryScope.USER, "기본", "기본 본문", false);

        JsonNode missing = body(call(dadToken, 999999L, null));
        assertThat(missing.path("result").path("isError").asBoolean()).isTrue();
        for (Memory unreadable : List.of(career, sensitive, hidden)) {
            HttpResponse<String> response = call(dadToken, unreadable.id(), null);
            assertThat(body(response).path("result")).isEqualTo(missing.path("result"));
            assertThat(response.body()).doesNotContain("커리어 본문", "민감 본문", "아이 본문");
        }
        assertThat(body(call(dadToken, core.id(), null))
                        .path("result")
                        .path("content")
                        .get(0)
                        .path("text")
                        .asString())
                .isEqualTo("기본 본문");
    }

    @Test
    @DisplayName("민감 허용을 받은 에이전트가 읽으면 암호화돼 저장된 민감 본문이 평문으로 나온다")
    void allowedAgentReadsSensitiveBodyAsPlaintext() throws Exception {
        Memory sensitive = memories.create(
                current(dad),
                MemoryScope.USER,
                "신원",
                "민감 본문",
                "core",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.SENSITIVE);
        agentCollections.save(AgentMemoryCollection.of(dadRun.agentId(), "core", true, Instant.now()));

        assertThat(jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, sensitive.id()))
                .doesNotContain("민감 본문");
        assertThat(body(call(dadToken, sensitive.id(), null))
                        .path("result")
                        .path("content")
                        .get(0)
                        .path("text")
                        .asString())
                .isEqualTo("민감 본문");
    }

    @Test
    @DisplayName("서명이 맞는 fos ctx 를 떼고 읽는다")
    void stripsValidlySignedFosCtxAndReads() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        String fosCtx = McpCallSigner.context(dadToken, "memory_read", dadRoot).toString();

        JsonNode read = body(send(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + indexed.id() + ",\"_fos_ctx\":" + fosCtx + "}}}",
                false));
        JsonNode wrongId = body(send(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":\"wrong\",\"_fos_ctx\":"
                        + fosCtx + "}}}",
                false));

        assertThat(read.path("result").path("isError").asBoolean()).isFalse();
        assertThat(read.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("아빠 본문");
        assertThat(wrongId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    @DisplayName("틀린 fos ctx 는 거절한다")
    void rejectsWrongFosCtx() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        String zeroSig = "{\"v\":1,\"session_id\":\"" + dadRoot + "\",\"root_session_id\":\"" + dadRoot
                + "\",\"tool_call_id\":\"c\",\"sig\":\"" + "0".repeat(64) + "\"}";

        JsonNode rejected = body(send(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + indexed.id() + ",\"_fos_ctx\":" + zeroSig + "}}}",
                false));
        JsonNode missing = body(send(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + indexed.id() + "}}}",
                false));

        assertThat(rejected.path("result").path("isError").asBoolean()).isTrue();
        assertThat(rejected.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
        assertThat(rejected.toString()).doesNotContain("아빠 본문");
        assertThat(missing).isEqualTo(rejected);
    }

    @Test
    @DisplayName("등록한 하위 에이전트는 부모 실행이 끝난 뒤에도 그 사용자로 읽고 등록이 없으면 거절한다")
    void registeredSubagentReadsAsItsUserAfterParentEndsAndUnregisteredIsRejected() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        String registered = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, registered);
        String unregistered = "하위-" + UUID.randomUUID();
        // 루트의 실행이 도는 중이어도 등록이 없는 하위 session 은 그 실행으로 되돌아가지 않는다.
        JsonNode rejectedWhileRunning = body(subagentRead(unregistered, indexed.id()));
        jdbc.update(
                "UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.SUCCEEDED.name(), dadRun.id());

        JsonNode read = body(subagentRead(registered, indexed.id()));
        JsonNode rejected = body(subagentRead(unregistered, indexed.id()));

        assertThat(read.path("result").path("isError").asBoolean())
                .as("등록한 하위 에이전트의 읽기: %s", read)
                .isFalse();
        assertThat(read.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("아빠 본문");
        JsonNode invalidContext = json.valueToTree(toolService.invalidContext());
        assertThat(rejectedWhileRunning.path("result"))
                .as("부모 실행이 도는 중 등록이 없는 하위 session 의 결과")
                .isEqualTo(invalidContext);
        assertThat(rejectedWhileRunning.toString()).doesNotContain("아빠 본문");
        assertThat(rejected.path("result")).as("등록이 없는 하위 session 의 결과").isEqualTo(invalidContext);
        assertThat(rejected.toString()).doesNotContain("아빠 본문");
    }

    @Test
    @DisplayName("등록한 하위 에이전트는 부모 실행이 취소되면 읽지 못한다")
    void registeredSubagentCannotReadWhenParentRunIsCancelled() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        String child = "하위-" + UUID.randomUUID();
        registrar.register(PROFILE, dadRoot, dadRoot, child);
        JsonNode whileRunning = body(subagentRead(child, indexed.id()));
        jdbc.update(
                "UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.CANCELLED.name(), dadRun.id());

        JsonNode afterCancel = body(subagentRead(child, indexed.id()));

        assertThat(whileRunning
                        .path("result")
                        .path("content")
                        .get(0)
                        .path("text")
                        .asString())
                .as("부모가 도는 동안의 읽기: %s", whileRunning)
                .isEqualTo("아빠 본문");
        assertThat(afterCancel.path("result"))
                .as("부모를 중지한 뒤의 결과")
                .isEqualTo(json.valueToTree(toolService.invalidContext()));
        assertThat(afterCancel.toString()).doesNotContain("아빠 본문");
    }

    @Test
    @DisplayName("인증한 요청에 토큰 원문이 아닌 해시를 속성으로 싣는다")
    void authenticatedRequestCarriesHashNotRawTokenAsAttribute() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        mvc.perform(post("/mcp")
                        .servletPath("/mcp")
                        .header("Authorization", "Bearer " + dadToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(request()
                        .attribute(
                                AgentTokenAuthenticationFilter.TOKEN_HASH_ATTRIBUTE, AgentTokenService.hash(dadToken)));
    }

    @Test
    @DisplayName("제안과 항상 주입하는 항목은 본문을 돌려주지 않는다")
    void doesNotReturnBodyOfProposalsAndAlwaysInjectedItems() throws Exception {
        Memory proposed = memories.proposeUser(current(dad), "제안", "승인 전", 1L);
        Memory always = memories.create(current(dad), MemoryScope.USER, "항상", "이미 주입됨", true);
        assertThat(body(call(dadToken, proposed.id(), null))
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isTrue();
        assertThat(body(call(dadToken, always.id(), null))
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("인증 Origin JSON RPC 오류를 HTTP에서 검사한다")
    void checksAuthOriginAndJsonRpcErrorsOverHttp() throws Exception {
        assertThat(mcp(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                        .statusCode())
                .isEqualTo(401);
        String revoked = tokens.issue(PROFILE, "revoked").rawToken();
        tokens.revoke(tokenRepository
                .findByTokenHash(AgentTokenService.hash(revoked))
                .orElseThrow()
                .id());
        assertThat(mcp(revoked, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                        .statusCode())
                .isEqualTo(401);
        assertThat(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", true)
                        .statusCode())
                .isEqualTo(403);
        assertThat(body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown\"}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32601);
        assertThat(body(mcp(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown\",\"arguments\":{\"id\":1}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32601);
        assertThat(body(mcp(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":\"wrong\"}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
        assertThat(body(mcp(
                                dadToken,
                                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":1.5}}}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);
    }

    @Test
    @DisplayName("관리자만 토큰을 발급하고 목록을 보고 폐기한다")
    void onlyAdminIssuesListsAndRevokesTokens() throws Exception {
        String adminJwt = jwt(dad);
        String memberJwt = jwt(kid);
        String issueBody = "{\"profileName\":\"kid-profile\",\"label\":\"profile\"}";

        assertThat(api("POST", "/api/v1/admin/agent-tokens", memberJwt, issueBody)
                        .statusCode())
                .isEqualTo(403);

        HttpResponse<String> issued = api("POST", "/api/v1/admin/agent-tokens", adminJwt, issueBody);
        assertThat(issued.statusCode()).isEqualTo(200);
        JsonNode issuedBody = json.readTree(issued.body());
        String rawToken = issuedBody.path("token").asString();
        long tokenId = issuedBody.path("id").asLong();
        assertThat(rawToken).isNotBlank();
        assertThat(issuedBody.path("profileName").asString()).isEqualTo("kid-profile");
        assertThat(issuedBody.has("userEmail"))
                .as("발급 응답에 사용자 칸이 없다: %s", issuedBody)
                .isFalse();

        HttpResponse<String> listed = api("GET", "/api/v1/admin/agent-tokens", adminJwt, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode listBody = json.readTree(listed.body());
        assertThat(listBody).hasSize(2);
        assertThat(listed.body()).doesNotContain(rawToken, "\"token\"");
        Map<Long, JsonNode> rows = rowsById(listBody);
        long dadTokenId = tokenRepository
                .findByTokenHash(AgentTokenService.hash(dadToken))
                .orElseThrow()
                .id();
        assertThat(rows.get(dadTokenId).path("profileName").asString()).isEqualTo(PROFILE);
        assertThat(rows.get(tokenId).path("profileName").asString()).isEqualTo("kid-profile");
        for (JsonNode row : listBody) {
            assertThat(row.has("userEmail")).as("목록 줄에 사용자 칸이 없다: %s", row).isFalse();
        }
        assertThat(api(
                                "POST",
                                "/api/v1/admin/agent-tokens",
                                adminJwt,
                                "{\"userEmail\":\"kid@example.com\",\"label\":\"old\"}")
                        .statusCode())
                .as("사용자로 발급하는 길은 없다")
                .isEqualTo(400);

        assertThat(api("DELETE", "/api/v1/admin/agent-tokens/" + tokenId, memberJwt, null)
                        .statusCode())
                .isEqualTo(403);
        assertThat(api("DELETE", "/api/v1/admin/agent-tokens/" + tokenId, adminJwt, null)
                        .statusCode())
                .isEqualTo(200);
        assertThat(mcp(rawToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                        .statusCode())
                .isEqualTo(401);
    }

    /** 아빠의 루트 아래 하위 에이전트 session 에서 부른 것처럼 서명한 {@code memory_read} 를 보낸다. */
    private HttpResponse<String> subagentRead(String sessionId, Long id) throws Exception {
        String fosCtx = McpCallSigner.context(dadToken, "memory_read", dadRoot, sessionId, "call_" + UUID.randomUUID())
                .toString();
        return send(
                dadToken,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + id + ",\"_fos_ctx\":" + fosCtx + "}}}",
                false);
    }

    private HttpResponse<String> call(String token, Long id, Long ignoredUserId) throws Exception {
        return mcp(
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + id + (ignoredUserId == null ? "" : ",\"user_id\":" + ignoredUserId) + "}}}");
    }

    /** 도구 호출이면 아빠의 도는 실행 루트로 서명한 {@code _fos_ctx} 를 붙여 보낸다. */
    private HttpResponse<String> mcp(String token, String request) throws Exception {
        return mcp(token, request, false);
    }

    private HttpResponse<String> mcp(String token, String request, boolean origin) throws Exception {
        return send(token, token == null ? request : McpCallSigner.withContext(request, token, dadRoot), origin);
    }

    /** 본문을 그대로 보낸다. */
    private HttpResponse<String> send(String token, String request, boolean origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (origin) {
            builder.header("Origin", "http://browser.example");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> api(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jwt(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.email())
                .claim("name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static Map<Long, JsonNode> rowsById(JsonNode list) {
        return StreamSupport.stream(list.spliterator(), false)
                .collect(Collectors.toMap(row -> row.path("id").asLong(), Function.identity()));
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
