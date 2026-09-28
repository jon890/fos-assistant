package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Hermes toolset 경로의 메서드, 인증, 본문 모양을 확인한다. */
class HermesToolsetRequestTest {

    private static final String DASHBOARD_TOKEN = "dashboard-token";
    private static final String PROFILE_KEY = "profile-key";
    private final List<Call> calls = new ArrayList<>();
    private HttpServer server;
    private HttpHermesToolsetClient client;
    private String response = "[]";

    private record Call(String method, String path, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HermesProperties properties = new HermesProperties(
                "keys", baseUrl(), DASHBOARD_TOKEN, "http://listener.test", Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
        when(keyStore.resolve("kid")).thenReturn(PROFILE_KEY);
        client = new HttpHermesToolsetClient(keyStore, properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 대시보드_목록은_기계용_토큰으로_읽는다() {
        response = "[{\"name\":\"web\",\"label\":\"Web\",\"description\":\"Search\"}]";

        assertThat(client.readCatalog()).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("web");
            assertThat(entry.label()).isEqualTo("Web");
        });
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/api/tools/toolsets");
            assertThat(call.authorization()).isEqualTo("Bearer " + DASHBOARD_TOKEN);
        });
    }

    @Test
    void listener_목록은_profile_key로_켜진_이름만_읽는다() {
        response = "[{\"name\":\"web\",\"enabled\":true},{\"name\":\"terminal\",\"enabled\":false}]";

        assertThat(client.readEnabled(baseUrl() + "/p/kid", "kid")).containsExactly("web");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/p/kid/v1/toolsets");
            assertThat(call.authorization()).isEqualTo("Bearer " + PROFILE_KEY);
        });
    }

    @Test
    void API_server_목록만_설정으로_쓴다() throws Exception {
        client.writeApiServer("kid", List.of("web", "fos-assistant-memory"));

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/config");
            assertThat(call.authorization()).isEqualTo("Bearer " + DASHBOARD_TOKEN);
            assertThat(new ObjectMapper().readValue(call.body(), Map.class)).isEqualTo(Map.of(
                    "profile", "kid",
                    "config", Map.of(
                            "platform_toolsets", Map.of("api_server", List.of("web", "fos-assistant-memory")))));
        });
    }

    @Test
    void listener가_빈_목록을_성공으로_돌려도_도구가_모두_꺼진_것으로_읽지_않는다() {
        response = "[]";

        assertThatThrownBy(() -> client.readEnabled(baseUrl() + "/p/kid", "kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
