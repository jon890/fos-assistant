package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpConnectionLiveness;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 실제 HTTP 경계에서 {@code /mcp} 요청이 그 토큰의 profile 연결을 살아 있다고 적는지 본다. Hermes 의 keepalive 는 {@code ping} 을 먼저 보내고, 이 서버가
 * 모르는 메서드로 답하면 {@code tools/list} 로 바꾼다. 두 요청 모두 연결이 살아 있다는 표시다.
 */
@BackendIntegrationTest
class McpConnectionLivenessHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    McpConnectionLiveness liveness;

    private final HttpClient client = HttpClient.newHttpClient();


    @Test
    @DisplayName("인증한 ping 과 tools/list 는 그 profile 의 연결을 살아 있다고 적는다")
    void authenticatedRequestsMarkTheProfileConnected() throws Exception {
        String pinged = "mcp-liveness-ping-" + UUID.randomUUID();
        String listed = "mcp-liveness-list-" + UUID.randomUUID();
        String pingToken = tokens.issue(pinged, "liveness").rawToken();
        String listToken = tokens.issue(listed, "liveness").rawToken();
        assertThat(liveness.connected(pinged)).isFalse();

        assertThat(send(pingToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").statusCode()).isEqualTo(200);
        assertThat(send(listToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").statusCode()).isEqualTo(200);

        assertThat(liveness.connected(pinged)).isTrue();
        assertThat(liveness.connected(listed)).isTrue();
    }


    @Test
    @DisplayName("토큰이 틀린 요청은 어느 profile 의 연결도 살리지 않는다")
    void rejectedRequestMarksNothing() throws Exception {
        String profile = "mcp-liveness-rejected-" + UUID.randomUUID();
        tokens.issue(profile, "liveness");

        assertThat(send("not-a-token", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").statusCode()).isEqualTo(401);

        assertThat(liveness.connected(profile)).isFalse();
    }


    private HttpResponse<String> send(String token, String request) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
