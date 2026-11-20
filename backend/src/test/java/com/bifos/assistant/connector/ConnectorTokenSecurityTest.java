package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 실제 HTTP 경계에서 토큰 통로가 사용자 JWT 없이 표식의 판정까지 가는지 본다.
 *
 * <p>없는 연결 번호로 서명한 표식을 쓴다. 인증을 지나 판정까지 가면 404 이고, 인증에 막혀도 표식이 틀려도 401 이다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.gateway-base-url=http://cp.example.test/internal/browser-gateway")
class ConnectorTokenSecurityTest {
    private static final String BASE = "http://cp.example.test";

    @LocalServerPort
    int port;

    @Autowired
    ConnectorOAuthSigner signer;

    private final HttpClient client = HttpClient.newHttpClient();


    @AfterEach
    void tearDown() {
        client.close();
    }


    @Test
    @DisplayName("JWT 없이 부른 토큰 통로는 표식으로만 판정하고 틀린 사용자 토큰이 와도 같다")
    void reachesPassageWithoutUserToken() throws Exception {
        String path = signer.currentUrl(999_999_999L).orElseThrow().substring(BASE.length());

        assertThat(send(HttpRequest.newBuilder(uri(path))).statusCode()).isEqualTo(404);
        assertThat(send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer not-a-jwt")).statusCode()).isEqualTo(404);
        HttpResponse<String> forged = send(HttpRequest.newBuilder(uri("/internal/connector-token/k1." + "0".repeat(64))));
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.body()).isEmpty();
    }


    @Test
    @DisplayName("통로 아래의 다른 경로와 다른 메서드는 인증 없이 받지 않는다")
    void otherPathsAndMethodsStayProtected() throws Exception {
        String path = signer.currentUrl(999_999_999L).orElseThrow().substring(BASE.length());

        assertThat(send(HttpRequest.newBuilder(uri(path + "/extra"))).statusCode()).isIn(401, 403);
        HttpRequest post = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
    }


    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }


    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
