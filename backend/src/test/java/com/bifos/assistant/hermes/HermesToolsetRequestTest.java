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
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("대시보드 목록은 기계용 토큰으로 읽는다")
    void dashboardListIsReadWithMachineToken() {
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
    @DisplayName("listener 목록은 profile key로 켜진 이름만 읽는다")
    void listenerListReadsOnlyEnabledNamesWithProfileKey() {
        response = "{\"object\":\"list\",\"platform\":\"api_server\",\"data\":["
                + "{\"name\":\"web\",\"enabled\":true},{\"name\":\"terminal\",\"enabled\":false}]}";

        assertThat(client.readEnabled(baseUrl() + "/p/kid", "kid")).containsExactly("web");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/p/kid/v1/toolsets");
            assertThat(call.authorization()).isEqualTo("Bearer " + PROFILE_KEY);
        });
    }

    @Test
    @DisplayName("API server 목록만 설정으로 쓴다")
    void usesOnlyApiServerListAsSetting() throws Exception {
        client.writeApiServer("kid", List.of("web", "fos-assistant"));

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.path()).isEqualTo("/api/config");
            assertThat(call.authorization()).isEqualTo("Bearer " + DASHBOARD_TOKEN);
            assertThat(new ObjectMapper().readValue(call.body(), Map.class)).isEqualTo(Map.of(
                    "profile", "kid",
                    "config", Map.of(
                            "platform_toolsets", Map.of("api_server", List.of("web", "fos-assistant")))));
        });
    }

    @Test
    @DisplayName("listener 목록이 data 로 감싸지 않은 배열이면 받지 않는다")
    void rejectsListenerListNotWrappedAsDataArray() {
        response = "[{\"name\":\"web\",\"enabled\":true}]";

        assertThatThrownBy(() -> client.readEnabled(baseUrl() + "/p/kid", "kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("listener가 빈 목록을 성공으로 돌려도 도구가 모두 꺼진 것으로 읽지 않는다")
    void doesNotReadAsAllToolsOffWhenListenerReturnsEmptyListAsSuccess() {
        response = "{\"object\":\"list\",\"platform\":\"api_server\",\"data\":[]}";

        assertThatThrownBy(() -> client.readEnabled(baseUrl() + "/p/kid", "kid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
