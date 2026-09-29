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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * 옮겨 가는 동안 설정이 참이면 profile 이 빈 옛 토큰이 전처럼 그 토큰의 사용자로 도는지 본다(ADR-032).
 *
 * <p>Control Plane 이 시작하지 않은 run 은 도는 부모 실행이 없다. 전환 동안에는 그런 run 에서 온 옛 토큰의
 * 호출도 전처럼 돌아야 한다. 같은 설정에서도 profile 이 묶인 토큰에는 옛 경로가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "assistant.mcp.legacy-user-tokens=true")
@ActiveProfiles("test")
class McpLegacyTokenTest {
    private static final String BOUND_PROFILE = "legacy-check";

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired MemoryRepository memoryRepository;
    @Autowired MemoryService memories;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String legacyToken;
    private long legacyTokenId;
    private Memory memory;

    @BeforeEach
    void 준비한다() {
        memoryRepository.deleteAll();
        tokenRepository.deleteAll();
        users.deleteAll();
        AppUser owner = users.save(AppUser.of("legacy-owner@example.com", "주인", 1L, UserRole.MEMBER));
        memory = memories.create(new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role()),
                MemoryScope.USER, "색인", "옛 토큰 주인의 본문", false);
        legacyToken = "legacy-" + UUID.randomUUID();
        legacyTokenId = McpCallSigner.insertLegacyToken(jdbc, owner.id(), legacyToken, "legacy");
    }

    @Test
    void 옛_토큰은_fos_ctx_없이_그_토큰의_사용자로_읽고_마지막_사용_시각을_남긴다() throws Exception {
        JsonNode result = body(send(legacyToken, memoryRead(null))).path("result");

        assertThat(result.path("isError").asBoolean()).as("읽기 결과: %s", result).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("옛 토큰 주인의 본문");
        assertThat(tokenRepository.findById(legacyTokenId).orElseThrow().lastUsedAt()).isNotNull();
    }

    @Test
    void 옛_토큰은_도는_실행이_없는_뿌리로_서명한_fos_ctx_가_붙어도_그_토큰의_사용자로_읽는다() throws Exception {
        ObjectNode fosCtx = McpCallSigner.context(legacyToken, "memory_read", "cron-session-1");

        JsonNode result = body(send(legacyToken, memoryRead(fosCtx))).path("result");

        assertThat(result.path("isError").asBoolean()).as("읽기 결과: %s", result).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("옛 토큰 주인의 본문");
    }

    @Test
    void 같은_설정에서도_profile_이_묶인_토큰은_fos_ctx_가_없으면_거절된다() throws Exception {
        String bound = tokens.issue(BOUND_PROFILE, "bound").rawToken();

        JsonNode result = body(send(bound, memoryRead(null))).path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
                .isEqualTo("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
    }

    private String memoryRead(ObjectNode fosCtx) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("id", memory.id());
        if (fosCtx != null) arguments.set("_fos_ctx", fosCtx);
        ObjectNode params = json.createObjectNode();
        params.put("name", "memory_read");
        params.set("arguments", arguments);
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        return json.writeValueAsString(request);
    }

    private HttpResponse<String> send(String token, String request) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP 상태, 본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
