package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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

/** 실행 하나를 한 번 묻는 조회가 Hermes 의 답을 네 가지로 나누는지 본다. */
class HermesRunLookupTest {

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private HttpHermesRunsClient client;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        when(keyStore.resolve("dad")).thenReturn("dad-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/p/dad";
        client = new HttpHermesRunsClient(
                keyStore,
                new HermesProperties(
                        "keys",
                        "https://hermes-dashboard.example.com",
                        "test-dashboard-token",
                        "https://hermes-listener.example.com",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("끝난 실행은 답과 토큰과 실제로 돈 모델을 담아 돌려준다")
    void finishedRunCarriesTheResult() {
        body.set("{\"status\":\"completed\",\"output\":\"답\",\"session_id\":\"s1\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12},"
                + "\"runtime\":{\"provider\":\"p\",\"model\":\"m\"}}");

        HermesRunLookup lookup = client.lookupRun(baseUrl, "dad", "run-1");

        assertThat(lookup.state()).isEqualTo(HermesRunLookup.State.FINISHED);
        assertThat(lookup.result().output()).isEqualTo("답");
        assertThat(lookup.result().usage().inputTokens()).isEqualTo(10);
        assertThat(lookup.result().runtime().model()).isEqualTo("m");
    }

    @Test
    @DisplayName("running 은 아직 돈다")
    void runningIsRunning() {
        body.set("{\"status\":\"running\"}");

        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.RUNNING);
    }

    @Test
    @DisplayName("stopping 과 waiting_for_approval 도 아직 돈다")
    void nonTerminalStatesAreRunning() {
        body.set("{\"status\":\"stopping\"}");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.RUNNING);

        body.set("{\"status\":\"waiting_for_approval\"}");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.RUNNING);
    }

    @Test
    @DisplayName("status 가 없거나 글이 아니어도 아직 돈다")
    void missingOrNonTextStatusIsRunning() {
        body.set("{}");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.RUNNING);

        body.set("{\"status\":7}");
        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.RUNNING);
    }

    @Test
    @DisplayName("interrupted 는 끝났지만 성공이 아니다")
    void interruptedIsFinishedButNotSucceeded() {
        body.set("{\"status\":\"interrupted\",\"error\":\"x\"}");

        HermesRunLookup lookup = client.lookupRun(baseUrl, "dad", "run-1");

        assertThat(lookup.state()).isEqualTo(HermesRunLookup.State.FINISHED);
        assertThat(lookup.result().succeeded()).isFalse();
    }

    @Test
    @DisplayName("404 는 Hermes 가 그 run 을 모른다")
    void notFoundWhenHermesDoesNotKnowTheRun() {
        status.set(404);

        assertThat(client.lookupRun(baseUrl, "dad", "run-1").state()).isEqualTo(HermesRunLookup.State.NOT_FOUND);
    }

    @Test
    @DisplayName("503 은 닿지 못한 것으로 예외를 올린다")
    void serviceUnavailableThrows() {
        status.set(503);

        assertThatThrownBy(() -> client.lookupRun(baseUrl, "dad", "run-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("429 는 붐벼서 거절당한 것으로 예외를 올린다")
    void tooManyRequestsThrowsBusy() {
        status.set(429);

        assertThatThrownBy(() -> client.lookupRun(baseUrl, "dad", "run-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BUSY);
    }

    @Test
    @DisplayName("run 경로를 그 profile 의 key 로 부른다")
    void callsTheRunPathWithTheProfileKey() {
        body.set("{\"status\":\"running\"}");

        client.lookupRun(baseUrl, "dad", "run-1");

        assertThat(requestedPath.get()).isEqualTo("/p/dad/v1/runs/run-1");
        assertThat(authorization.get()).isEqualTo("Bearer dad-key");
    }
}
