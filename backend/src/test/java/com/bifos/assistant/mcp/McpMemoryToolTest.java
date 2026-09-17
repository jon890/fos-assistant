package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 경계에서 MCP 인증과 JSON-RPC 계약을 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpMemoryToolTest {
    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired MemoryRepository memoryRepository;
    @Autowired MemoryService memories;
    @Autowired BuildProperties buildProperties;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser kid;
    private String dadToken;
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-test-secret";

    @BeforeEach void 준비한다() {
        memoryRepository.deleteAll(); tokenRepository.deleteAll(); users.deleteAll();
        dad = users.save(AppUser.of("dad@example.com", "아빠", 1L, UserRole.ADMIN));
        kid = users.save(AppUser.of("kid@example.com", "아이", 1L, UserRole.MEMBER));
        dadToken = tokens.issue(dad.email(), "dad").rawToken();
    }

    @Test void initialize_목록과_알림은_계약한_응답을_낸다() throws Exception {
        JsonNode initialized = body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"initialize\"}"));
        assertThat(initialized.path("id").asInt()).isEqualTo(17);
        assertThat(initialized.path("result").path("protocolVersion").asString()).isEqualTo("2025-03-26");
        assertThat(initialized.path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isFalse();
        assertThat(initialized.path("result").path("serverInfo").path("name").asString()).isEqualTo("fos-assistant-memory");
        assertThat(initialized.path("result").path("serverInfo").path("version").asString())
                .isEqualTo(buildProperties.getVersion());
        JsonNode listed = body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"tools/list\"}"));
        assertThat(listed.path("id").asInt()).isEqualTo(18);
        assertThat(listed.path("result").path("tools")).hasSize(1);
        assertThat(listed.path("result").path("tools").get(0).path("name").asString()).isEqualTo("memory_read");
        HttpResponse<String> notification = mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(notification.statusCode()).isEqualTo(202); assertThat(notification.body()).isEmpty();
    }

    @Test void 토큰의_사용자만_정하고_권한_오류는_동일하게_숨긴다() throws Exception {
        Memory indexed = memories.create(current(dad), MemoryScope.USER, "색인", "아빠 본문", false);
        Memory hidden = memories.create(current(kid), MemoryScope.USER, "비밀", "아이 본문", false);
        JsonNode own = body(call(dadToken, indexed.id(), 999L));
        assertThat(own.path("result").path("isError").asBoolean()).isFalse();
        assertThat(own.path("result").path("content").get(0).path("text").asString()).isEqualTo("아빠 본문");
        JsonNode unauthorized = body(call(dadToken, hidden.id(), null));
        JsonNode missing = body(call(dadToken, 999999L, null));
        assertThat(unauthorized.path("result")).isEqualTo(missing.path("result"));
    }

    @Test void 제안과_항상_주입하는_항목은_본문을_돌려주지_않는다() throws Exception {
        Memory proposed = memories.proposeUser(current(dad), "제안", "승인 전", 1L);
        Memory always = memories.create(current(dad), MemoryScope.USER, "항상", "이미 주입됨", true);
        assertThat(body(call(dadToken, proposed.id(), null)).path("result").path("isError").asBoolean()).isTrue();
        assertThat(body(call(dadToken, always.id(), null)).path("result").path("isError").asBoolean()).isTrue();
    }

    @Test void 인증_Origin_JSON_RPC_오류를_HTTP에서_검사한다() throws Exception {
        assertThat(mcp(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").statusCode()).isEqualTo(401);
        String revoked = tokens.issue(dad.email(), "revoked").rawToken();
        tokens.revoke(tokenRepository.findByTokenHash(AgentTokenService.hash(revoked)).orElseThrow().id());
        assertThat(mcp(revoked, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").statusCode()).isEqualTo(401);
        assertThat(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", true).statusCode()).isEqualTo(403);
        assertThat(body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown\"}")).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown\",\"arguments\":{\"id\":1}}}")).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":\"wrong\"}}}")).path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(body(mcp(dadToken, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":1.5}}}")).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test void 관리자만_토큰을_발급하고_목록을_보고_폐기한다() throws Exception {
        String adminJwt = jwt(dad);
        String memberJwt = jwt(kid);
        String issueBody = "{\"userEmail\":\"kid@example.com\",\"label\":\"profile\"}";

        assertThat(api("POST", "/api/v1/admin/agent-tokens", memberJwt, issueBody).statusCode())
                .isEqualTo(403);

        HttpResponse<String> issued = api(
                "POST", "/api/v1/admin/agent-tokens", adminJwt, issueBody);
        assertThat(issued.statusCode()).isEqualTo(200);
        JsonNode issuedBody = json.readTree(issued.body());
        String rawToken = issuedBody.path("token").asString();
        long tokenId = issuedBody.path("id").asLong();
        assertThat(rawToken).isNotBlank();

        HttpResponse<String> listed = api(
                "GET", "/api/v1/admin/agent-tokens", adminJwt, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode listBody = json.readTree(listed.body());
        assertThat(listBody).hasSize(2);
        assertThat(listed.body()).doesNotContain(rawToken, "\"token\"");

        assertThat(api("DELETE", "/api/v1/admin/agent-tokens/" + tokenId, memberJwt, null)
                .statusCode()).isEqualTo(403);
        assertThat(api("DELETE", "/api/v1/admin/agent-tokens/" + tokenId, adminJwt, null)
                .statusCode()).isEqualTo(200);
        assertThat(mcp(rawToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> call(String token, Long id, Long ignoredUserId) throws Exception { return mcp(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_read\",\"arguments\":{\"id\":" + id + (ignoredUserId == null ? "" : ",\"user_id\":" + ignoredUserId) + "}}}"); }
    private HttpResponse<String> mcp(String token, String request) throws Exception { return mcp(token, request, false); }
    private HttpResponse<String> mcp(String token, String request, boolean origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (origin) builder.header("Origin", "http://browser.example");
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
    private JsonNode body(HttpResponse<String> response) { assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()); }
    private static CurrentUser current(AppUser user) { return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role()); }
}
