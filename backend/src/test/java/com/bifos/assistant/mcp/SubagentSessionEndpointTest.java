package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 하위 에이전트 session 등록 경로의 인증과 응답 계약을 확인한다(ADR-037).
 *
 * <p>계약은 {@code docs/hermes/fos-ctx.md} 의 「하위 에이전트 session 등록 계약」 이다. 본문 서명은 운영 코드가
 * 아니라 {@link McpCallSigner} 가 따로 계산한다. 토큰 없이 부른 요청이 Spring Security 의 기본 거절이 아니라
 * {@code 401} 로 끝나는 것과 정상 등록이 {@code 201} 인 것으로 MCP 토큰 필터가 이 경로를 거르는 것을 본다.
 *
 * <p>번호가 붙은 검사는 리뷰가 요구한 필수 검사의 번호다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubagentSessionEndpointTest {
    private static final String PATH = "/internal/hermes/session-bindings/subagent";
    private static final String PROFILE_A = "subagent-endpoint-a";
    private static final String PROFILE_B = "subagent-endpoint-b";
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

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
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser kid;
    private String tokenA;
    private String rootA;
    private String rootB;
    private AgentExecution dadRun;

    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE_A, PROFILE_B));
        memoryRepository.deleteAll();
        tokenRepository.deleteAll();
        users.deleteAll();
        dad = users.save(AppUser.of("subagent-dad@example.com", "아빠", 1L, UserRole.ADMIN));
        kid = users.save(AppUser.of("subagent-kid@example.com", "아이", 1L, UserRole.MEMBER));
        tokenA = tokens.issue(PROFILE_A, "a").rawToken();
        tokens.issue(PROFILE_B, "b");
        rootA = McpCallSigner.newRoot();
        rootB = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, agents, dad.id(), 1L, PROFILE_A, rootA);
        McpCallSigner.running(executions, agents, kid.id(), 2L, PROFILE_B, rootB);
    }

    @Test
    @DisplayName("도는 실행 아래 최상위 자식을 등록하면 201 이다")
    void registeringTopLevelChildUnderRunningRunIs201() throws Exception {
        String child = newChild();

        HttpResponse<String> response = register(
                tokenA, McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString());

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(201);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"result\":\"created\"}"));
        assertThat(rows(PROFILE_A, child)).isEqualTo(1);
    }

    @Test // 9
    @DisplayName("같은 본문을 다시 보내면 200 이고 줄은 하나다")
    void resendingSameBodyIs200AndKeepsOneRow() throws Exception {
        String child = newChild();
        String body = McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString();
        register(tokenA, body);

        HttpResponse<String> again = register(tokenA, body);

        assertThat(again.statusCode()).as("응답: %s", again.body()).isEqualTo(200);
        assertThat(json.readTree(again.body())).isEqualTo(json.readTree("{\"result\":\"exists\"}"));
        assertThat(rows(PROFILE_A, child)).isEqualTo(1);
    }

    @Test // 10
    @DisplayName("같은 자식을 다른 루트의 도는 실행 아래로 보내면 409 이고 origin 은 그대로다")
    void sendingSameChildUnderOtherRootsRunningRunIs409AndOriginUnchanged() throws Exception {
        String child = newChild();
        register(tokenA, McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString());
        String otherRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, agents, kid.id(), 3L, PROFILE_A, otherRoot);

        HttpResponse<String> response = register(
                tokenA,
                McpCallSigner.subagentBody(tokenA, otherRoot, otherRoot, child).toString());

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("SESSION_BINDING_CONFLICT");
        assertThat(origin(PROFILE_A, child)).as("origin 실행").isEqualTo(dadRun.id());
    }

    @Test
    @DisplayName("서명이나 모양이 틀리거나 JSON 이 아니거나 비어 있으면 403 이다")
    void badSignatureShapeNonJsonOrEmptyIs403() throws Exception {
        String child = newChild();
        ObjectNode wrongSig = McpCallSigner.subagentBody(tokenA, rootA, rootA, child);
        wrongSig.put("sig", "0".repeat(64));
        ObjectNode wrongShape = McpCallSigner.subagentBody(tokenA, rootA, rootA, child);
        wrongShape.put("v", "1");

        for (String body : List.of(wrongSig.toString(), wrongShape.toString(), "not json", "[1]", "")) {
            HttpResponse<String> response = register(tokenA, body);
            assertThat(response.statusCode())
                    .as("본문 %s 의 응답: %s", body, response.body())
                    .isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asString())
                    .as("본문 %s", body)
                    .isEqualTo("SESSION_BINDING_REJECTED");
        }
        assertThat(rows(PROFILE_A, child)).isZero();
    }

    @Test // 8
    @DisplayName("profile A 토큰으로 profile B 실행의 루트 아래 등록하면 403 이고 줄이 없다")
    void registeringUnderRootOfProfileBRunWithProfileATokenIs403AndNoRow() throws Exception {
        String child = newChild();

        HttpResponse<String> response = register(
                tokenA, McpCallSigner.subagentBody(tokenA, rootB, rootB, child).toString());

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("SESSION_BINDING_REJECTED");
        assertThat(rows(PROFILE_A, child) + rows(PROFILE_B, child)).isZero();
    }

    @Test
    @DisplayName("토큰이 없거나 모르거나 폐기됐으면 401 이고 본문이 없다")
    void missingUnknownOrRevokedTokenIs401WithoutBody() throws Exception {
        String revoked = tokens.issue(PROFILE_A, "revoked").rawToken();
        tokens.revoke(tokenRepository
                .findByTokenHash(AgentTokenService.hash(revoked))
                .orElseThrow()
                .id());
        String unknown = "unknown-" + UUID.randomUUID();
        String child = newChild();

        HttpResponse<String> none = register(
                null, McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString());
        HttpResponse<String> notIssued = register(
                unknown,
                McpCallSigner.subagentBody(unknown, rootA, rootA, child).toString());
        HttpResponse<String> gone = register(
                revoked,
                McpCallSigner.subagentBody(revoked, rootA, rootA, child).toString());

        for (HttpResponse<String> response : List.of(none, notIssued, gone)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }
        assertThat(rows(PROFILE_A, child)).isZero();
    }

    @Test
    @DisplayName("사용자 JWT 로 부르면 MCP 토큰 필터가 받아 401 이고 줄이 없다")
    void userJwtIs401ByMcpTokenFilterAndLeavesNoRow() throws Exception {
        String jwt = jwt(dad);
        String child = newChild();

        HttpResponse<String> response = register(
                jwt, McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(rows(PROFILE_A, child)).isZero();
    }

    @Test
    @DisplayName("Origin 헤더가 있으면 403 이다")
    void originHeaderIs403() throws Exception {
        String child = newChild();

        HttpResponse<String> response = send(
                PATH,
                tokenA,
                McpCallSigner.subagentBody(tokenA, rootA, rootA, child).toString(),
                true);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(rows(PROFILE_A, child)).isZero();
    }

    @Test // 1
    @DisplayName("등록한 자식은 부모 실행이 끝난 뒤에도 memory read 로 부모 사용자의 본문을 읽는다")
    void registeredChildReadsParentUsersBodyByMemoryReadEvenAfterParentEnds() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        String child = newChild();
        assertThat(register(
                                tokenA,
                                McpCallSigner.subagentBody(tokenA, rootA, rootA, child)
                                        .toString())
                        .statusCode())
                .isEqualTo(201);
        jdbc.update(
                "UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.SUCCEEDED.name(), dadRun.id());
        String fosCtx = McpCallSigner.context(tokenA, "memory_read", rootA, child, "call_" + UUID.randomUUID())
                .toString();

        HttpResponse<String> response = send(
                "/mcp",
                tokenA,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":"
                        + indexed.id() + ",\"_fos_ctx\":" + fosCtx + "}}}",
                false);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(response.body()).path("result");
        assertThat(result.path("isError").asBoolean()).as("읽기 결과: %s", result).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("아빠 본문");
    }

    @Test
    @DisplayName("등록 경로는 도구 목록에 나오지 않는다")
    void registrationPathIsNotInToolList() throws Exception {
        HttpResponse<String> response =
                send("/mcp", tokenA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", false);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode listed = json.readTree(response.body()).path("result").path("tools");
        assertThat(listed.isEmpty()).as("도구 목록: %s", listed).isFalse();
        for (JsonNode tool : listed) {
            assertThat(tool.path("name").asString()).as("도구 이름").doesNotContain("subagent", "session");
        }
        assertThat(response.body()).doesNotContain(PATH, "session-bindings");
    }

    private HttpResponse<String> register(String token, String body) throws Exception {
        return send(PATH, token, body, false);
    }

    private HttpResponse<String> send(String path, String token, String body, boolean origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (origin) {
            builder.header("Origin", "http://browser.example");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int rows(String profileName, String sessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermes_session_binding WHERE profile_name = ? AND session_id = ?",
                Integer.class,
                profileName,
                sessionId);
    }

    private Long origin(String profileName, String sessionId) {
        return jdbc.queryForObject(
                "SELECT origin_execution_id FROM hermes_session_binding WHERE profile_name = ? AND session_id = ?",
                Long.class,
                profileName,
                sessionId);
    }

    private static String newChild() {
        return "하위-" + UUID.randomUUID();
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

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
