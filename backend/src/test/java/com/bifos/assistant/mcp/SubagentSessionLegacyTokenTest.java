package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옛 토큰을 받아 주는 설정에서도 하위 에이전트 session 등록은 profile 에 묶인 토큰만 받는 것을 본다(ADR-037).
 *
 * <p>옛 토큰에는 profile 이 없어 등록을 profile 로 묶을 수 없다. 설정이 참이면 인증은 통과하므로 등록 경로가
 * 스스로 거절해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "assistant.mcp.legacy-user-tokens=true")
@ActiveProfiles("test")
class SubagentSessionLegacyTokenTest {
    private static final String PATH = "/internal/hermes/session-bindings/subagent";
    private static final String PROFILE = "subagent-legacy";

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired AgentExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser owner;
    private String root;

    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        tokenRepository.deleteAll();
        users.findByEmail("subagent-legacy@example.com").ifPresent(users::delete);
        owner = users.save(AppUser.of("subagent-legacy@example.com", "주인", 1L, UserRole.MEMBER));
        root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, owner.id(), 1L, PROFILE, root);
    }

    @Test
    void 옛_토큰은_서명이_맞아도_403_이고_줄이_없다() throws Exception {
        String legacy = "legacy-" + UUID.randomUUID();
        McpCallSigner.insertLegacyToken(jdbc, owner.id(), legacy, "legacy");
        String child = "하위-" + UUID.randomUUID();

        HttpResponse<String> response = register(legacy, McpCallSigner.subagentBody(legacy, root, root, child).toString());

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("SESSION_BINDING_REJECTED");
        assertThat(rows(child)).isZero();
    }

    @Test
    void 같은_설정에서도_profile_에_묶인_토큰의_등록은_201_이다() throws Exception {
        String bound = tokens.issue(PROFILE, "bound").rawToken();
        String child = "하위-" + UUID.randomUUID();

        HttpResponse<String> response = register(bound, McpCallSigner.subagentBody(bound, root, root, child).toString());

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(201);
        assertThat(rows(child)).isEqualTo(1);
    }

    private HttpResponse<String> register(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int rows(String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hermes_session_binding WHERE profile_name = ? AND session_id = ?",
                Integer.class, PROFILE, sessionId);
    }
}
