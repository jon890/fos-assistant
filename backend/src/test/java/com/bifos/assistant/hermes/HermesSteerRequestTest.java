package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.runtime.application.dto.SteerOutcome;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HermesProfileKeyStore;
import com.bifos.assistant.hermes.infra.HttpHermesRunsClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 도는 실행에 글을 끼워 넣는 요청과 응답을 본다. 남은 글은 종료 사건 계약 테스트가 확인한다. */
class HermesSteerRequestTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final HttpHermesRunsClient client = new HttpHermesRunsClient(
        keyStore,
        new HermesProperties(
            "keys",
            "https://hermes-dashboard.example.com",
            "test-dashboard-token",
            "https://hermes-listener.example.com",
            Duration.ofMillis(10),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5)),
        Duration.ofMillis(300));

    private volatile int steerStatus = 200;
    private volatile String steerBody = "{\"object\":\"hermes.run.steer\",\"run_id\":\"run-1\",\"accepted\":true}";
    private volatile long steerDelayMillis;
    private volatile String runBody = "{\"run_id\":\"run-1\",\"status\":\"completed\",\"output\":\"답\"}";

    private HttpServer server;
    private String baseUrl;


    @BeforeEach
    void start() throws IOException {
        when(keyStore.resolve("dad")).thenReturn("dad-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/p/dad";
        server.createContext("/p/dad/v1/runs/run-1/steer", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sleep(steerDelayMillis);
            respond(exchange, steerStatus, steerBody);
        });
        server.createContext("/p/dad/v1/runs/run-1", exchange -> respond(exchange, 200, runBody));
        server.start();
    }


    @AfterEach
    void stop() {
        server.stop(0);
    }


    @Test
    @DisplayName("받았다는 답이면 받음이고 그 run 경로에 글과 profile key 를 싣는다")
    void acceptedWhenHermesAcceptsAndSendsTextWithProfileKey() {
        SteerOutcome outcome = client.steer(baseUrl, "dad", "run-1", "2만원 이하로");

        assertThat(outcome).isEqualTo(SteerOutcome.ACCEPTED);
        assertThat(receivedPath.get()).isEqualTo("/p/dad/v1/runs/run-1/steer");
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer dad-key");
        assertThat(JSON.readTree(receivedBody.get()).path("input").asString()).isEqualTo("2만원 이하로");
    }


    @Test
    @DisplayName("409 로 받지 않으면 거절이다")
    void rejectedWhenRunIsNotAcceptingSteer() {
        steerStatus = 409;
        steerBody = "{\"error\":{\"code\":\"run_not_accepting_steer\"}}";

        assertThat(client.steer(baseUrl, "dad", "run-1", "글")).isEqualTo(SteerOutcome.REJECTED);
    }


    @Test
    @DisplayName("500 으로 답해도 받지 않았다는 답이라 거절이다")
    void rejectedWhenAgentFailedToSteer() {
        steerStatus = 500;
        steerBody = "{\"error\":{\"code\":\"steer_failed\"}}";

        assertThat(client.steer(baseUrl, "dad", "run-1", "글")).isEqualTo(SteerOutcome.REJECTED);
    }


    @Test
    @DisplayName("제한 시간 안에 답이 없으면 받았는지 모른다")
    void unknownWhenNoAnswerWithinTimeout() {
        steerDelayMillis = 1500;

        assertThat(client.steer(baseUrl, "dad", "run-1", "글")).isEqualTo(SteerOutcome.UNKNOWN);
    }


    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }


    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
