package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * 사용자가 걸린 MCP 호출은 요청자, origin 실행, 확인한 {@code _fos_ctx} 없이 도구에 닿지 못하는 것을 실제 HTTP 경계에서
 * 본다(ADR-032, ADR-037).
 *
 * <p>profile 이 빈 토큰은 인증에서 막혀 어느 도구에도, 하위 에이전트 session 등록에도 닿지 못하고 사용 시각도 남기지
 * 않는다. profile 에 묶인 토큰도 {@code _fos_ctx} 가 없으면 모든 도구가 같은 호출 맥락 오류로 끝난다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpCallerInvariantTest {
    private static final String PROFILE = "caller-invariant";
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String unboundToken;
    private long unboundTokenId;

    @BeforeEach
    void 준비한다() {
        tokenRepository.deleteAll();
        unboundToken = "unbound-" + UUID.randomUUID();
        unboundTokenId = McpCallSigner.insertUnboundToken(jdbc, unboundToken, "unbound");
    }

    @Test
    void profile_이_빈_토큰은_목록과_네_도구_모두_401_이고_사용_시각을_남기지_않는다() throws Exception {
        Map<String, String> requests = new LinkedHashMap<>();
        requests.put("tools/list", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        toolArguments().forEach((name, arguments) -> {
            requests.put(name + " _fos_ctx 없음", toolCall(name, arguments.deepCopy()));
            ObjectNode signed = arguments.deepCopy();
            signed.set("_fos_ctx", McpCallSigner.context(unboundToken, name, "cron-session-1"));
            requests.put(name + " _fos_ctx 있음", toolCall(name, signed));
        });

        for (Map.Entry<String, String> request : requests.entrySet()) {
            HttpResponse<String> response = send(unboundToken, request.getValue());
            assertThat(response.statusCode()).as("%s 의 HTTP 상태, 본문: %s", request.getKey(), response.body()).isEqualTo(401);
        }
        assertThat(tokenRepository.findById(unboundTokenId).orElseThrow().lastUsedAt()).as("profile 이 빈 토큰의 사용 시각").isNull();
    }

    @Test
    void profile_이_빈_토큰은_하위_에이전트_session_을_등록하지_못한다() throws Exception {
        String root = McpCallSigner.newRoot();
        String child = "하위-" + UUID.randomUUID();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/hermes/session-bindings/subagent"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + unboundToken)
                        .POST(HttpRequest.BodyPublishers.ofString(McpCallSigner.subagentBody(unboundToken, root, root, child).toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("등록 응답: %s", response.body()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermes_session_binding WHERE session_id = ?", Integer.class, child))
                .as("등록 줄 수").isZero();
        assertThat(tokenRepository.findById(unboundTokenId).orElseThrow().lastUsedAt()).as("profile 이 빈 토큰의 사용 시각").isNull();
    }

    @Test
    void profile_에_묶인_토큰도_fos_ctx_가_없으면_네_도구_모두_호출_맥락_오류다() throws Exception {
        String bound = tokens.issue(PROFILE, "bound").rawToken();

        for (Map.Entry<String, ObjectNode> tool : toolArguments().entrySet()) {
            HttpResponse<String> response = send(bound, toolCall(tool.getKey(), tool.getValue()));
            assertThat(response.statusCode()).as("%s 의 HTTP 상태, 본문: %s", tool.getKey(), response.body()).isEqualTo(200);
            JsonNode result = json.readTree(response.body()).path("result");
            assertThat(result.path("isError").asBoolean()).as("%s 의 결과: %s", tool.getKey(), result).isTrue();
            assertThat(result.path("content").get(0).path("text").asString()).as("%s 의 문구", tool.getKey()).isEqualTo(INVALID_CONTEXT);
        }
    }

    /** 요청자 판정이 인자 검사보다 먼저이므로 인자 모양과 무관하게 같은 결과여야 한다. */
    private Map<String, ObjectNode> toolArguments() {
        Map<String, ObjectNode> arguments = new LinkedHashMap<>();
        arguments.put("memory_read", json.createObjectNode().put("id", 1));
        arguments.put("artifact_write", json.createObjectNode());
        arguments.put("agent_list", json.createObjectNode());
        arguments.put("agent_status", json.createObjectNode().put("execution_id", 1));
        arguments.put("agent_stop", json.createObjectNode().put("execution_id", 1));
        return arguments;
    }

    private String toolCall(String name, JsonNode arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
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
}
