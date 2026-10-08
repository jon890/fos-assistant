package com.bifos.assistant.browser.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 실제 HTTP 경계에서 브라우저 중계 경로가 사용자 JWT 없이 중계의 판정까지 가는지 본다.
 *
 * <p>시험 설정은 사용자 브라우저와 중계가 꺼져 있다. 그래서 인증을 지나 판정까지 가면 503 이고, 인증에 막히면 401 이다. JDK
 * {@code HttpClient} 는 요청마다 {@code Upgrade: h2c} 를 싣는다. 받기 매핑이 그런 요청도 받는지를 여기서 함께 본다.
 */
@BackendIntegrationTest
class BrowserGatewaySecurityTest {

    private static final String TOKEN = "b1." + "0".repeat(64);

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    @DisplayName("JWT 없이 부른 중계 경로는 401 이 아니라 중계의 판정을 받는다")
    void reachesGatewayWithoutUserToken() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/json/version")));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("틀린 사용자 토큰이 실려도 중계 경로는 표식으로만 판정한다")
    void ignoresUserTokenOnGateway() throws Exception {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/json/version")).header("Authorization", "Bearer not-a-jwt"));

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("받기로 하지 않은 중계 경로는 실제 서버에서도 빈 본문의 404 이다")
    void answersEmptyNotFoundForOtherPaths() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/json/protocol")));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/internal/browser-gateway/" + TOKEN + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
