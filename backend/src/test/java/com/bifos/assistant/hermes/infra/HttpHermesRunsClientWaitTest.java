package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.runtime.domain.RunWait;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 결과를 기다리는 한도가 기다림 종류를 따르는지 본다({@code docs/features/execution.md} 의 「실행 한도」).
 *
 * <p>대역 서버는 실행 조회에 정한 횟수만큼 {@code running} 을 답한 뒤 {@code completed} 를 답한다. 조회 간격이 10ms 라 그 횟수가
 * 대화 한도(100ms)를 넘기고 긴 한도(5s) 안에 든다.
 */
class HttpHermesRunsClientWaitTest {

    /** 끝나기 전에 {@code running} 을 답하는 횟수다. 조회 간격 10ms 로 300ms 넘게 걸린다. */
    private static final int RUNNING_ANSWERS = 30;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger lookups = new AtomicInteger();
    private HttpServer server;
    private String baseUrl;
    private HttpHermesRunsClient client;


    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        HermesProfileKeyStore keys = mock(HermesProfileKeyStore.class);
        when(keys.resolve("dad")).thenReturn("dad-key");
        HermesProperties properties = new HermesProperties(
            "keys",
            "https://hermes-dashboard.example.com",
            "test-dashboard-token",
            "https://hermes-listener.example.com",
            Duration.ofMillis(10),
            Duration.ofMillis(100),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));
        HermesRunReplay replay = new HermesRunReplay(keys, properties, JsonMapper.builder().build(), Duration.ofSeconds(2));
        client = new HttpHermesRunsClient(keys, properties, Duration.ofSeconds(1), new HermesRunTerminals(), replay);
    }


    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }


    @Test
    @DisplayName("사람이 기다리는 실행은 hermes.run-timeout 에서 HERMES_RUN_TIMEOUT 으로 끊는다")
    void conversationWaitStopsAtRunTimeout() {
        assertThatThrownBy(() -> client.awaitCompletion(command(), "run-1", RunWait.CONVERSATION))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_RUN_TIMEOUT));
        assertThat(lookups.get()).isLessThan(RUNNING_ANSWERS);
    }


    @Test
    @DisplayName("한도를 고르지 않은 기다림은 사람이 기다리는 실행의 한도를 쓴다")
    void defaultWaitIsConversation() {
        assertThatThrownBy(() -> client.awaitCompletion(command(), "run-1"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_RUN_TIMEOUT));
    }


    @Test
    @DisplayName("사람이 기다리지 않는 실행은 hermes.background-run-timeout 까지 기다려 끝난 결과를 받는다")
    void backgroundWaitOutlastsRunTimeout() {
        HermesRunResult result = client.awaitCompletion(command(), "run-1", RunWait.BACKGROUND);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEqualTo("답");
        assertThat(lookups.get()).isGreaterThan(RUNNING_ANSWERS);
    }


    private HermesRunCommand command() {
        return new HermesRunCommand("dad", baseUrl, "안녕", null, null, null, null, null);
    }


    private void answer(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        int status = 404;
        String body = "{}";
        if ("GET /v1/runs/run-1".equals(key)) {
            status = 200;
            body = lookups.incrementAndGet() > RUNNING_ANSWERS
                ? "{\"status\":\"completed\",\"session_id\":\"s1\",\"output\":\"답\",\"usage\":{}}"
                : "{\"status\":\"running\",\"session_id\":\"s1\"}";
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
