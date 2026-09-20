package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 대시보드를 부를 때 나가는 메서드와 경로와 본문과 토큰을 본다.
 *
 * <p>여기가 어긋나면 대역은 통과하고 운영에서만 401 이나 404 가 난다. 기계용 토큰은 경로 문자열이
 * 정확히 같은 요청만 열어 주기 때문이다.
 */
class HermesDashboardRequestTest {

    private static final String TOKEN = "test-dashboard-token";

    private final List<Call> calls = new ArrayList<>();

    private HttpServer server;
    private HttpHermesDashboardClient client;
    private int status = 200;
    private String responseBody = "{}";

    private record Call(String method, String path, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(
                    new Call(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(
                                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        client = clientFor(baseUrl());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respondWith(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    private HttpHermesDashboardClient clientFor(String baseUrl) {
        return new HttpHermesDashboardClient(
                new HermesProperties(
                        "keys",
                        baseUrl,
                        TOKEN,
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
    }

    @Test
    void profile_을_만들_때_이름만_싣고_본뜰_profile_을_주지_않는다() {
        client.createProfile("kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.path()).isEqualTo("/api/profiles");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(call.body()).isEqualTo("{\"name\":\"kid\"}");
        });
    }

    @Test
    void 환경_값은_profile_과_이름과_값을_함께_싣는다() {
        client.putEnv("kid", "API_SERVER_MODEL_NAME", "kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/env");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(call.body())
                    .contains("\"profile\":\"kid\"")
                    .contains("\"key\":\"API_SERVER_MODEL_NAME\"")
                    .contains("\"value\":\"kid\"");
        });
    }

    @Test
    void profile_을_지울_때_그_이름이_경로에_붙는다() {
        client.deleteProfile("kid");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(call.path()).isEqualTo("/api/profiles/kid");
            assertThat(call.authorization()).isEqualTo("Bearer " + TOKEN);
        });
    }

    @Test
    void 주소_끝에_빗금이_붙어_있어도_경로가_겹치지_않는다() {
        clientFor(baseUrl() + "/").createProfile("kid");

        assertThat(calls)
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/api/profiles"));
    }

    @Test
    void 이미_쓰이는_이름이면_그것으로_알린다() {
        respondWith(409, "{\"error\":\"profile already exists\"}");

        assertThatThrownBy(() -> client.createProfile("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_PROFILE_EXISTS);
    }

    @Test
    void 다른_실패는_닿지_못한_것으로_남는다() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.createProfile("kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }
}
