package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공유 gateway 의 동시 실행 한도에 닿아 429 로 거절당한 것을 다른 실패와 구분하는지 본다.
 *
 * <p>같은 값으로 적으면 사용량 기록에서 Hermes 가 내려간 것과 붐빈 것을 나눌 수 없다. 한도를 올려야
 * 하는지 판단할 근거가 그 구분이다.
 */
class HermesBusyTest {

    /** 실제 Hermes 가 한도를 넘겼을 때 내는 본문이다. 실측한 것이다. */
    private static final String RATE_LIMITED = """
            {"error":{"message":"Too many concurrent runs (max 16)",\
            "type":"rate_limit_error","code":"rate_limit_exceeded"}}""";

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private HttpHermesRunsClient client;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        when(keyStore.resolve("dad")).thenReturn("dad-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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

    /** 상태 코드 하나만 내는 대역을 열고, 부른 횟수를 센다. */
    private void respondWith(int status, String body) {
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @Test
    @DisplayName("실행 제출이 429 를 받으면 붐빈다고 적는다")
    void notesBusyWhenRunSubmitGets429() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.submit(command()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BUSY);
    }

    @Test
    @DisplayName("붐빈다는 응답에 다시 보내지 않는다")
    void doesNotResendOnBusyResponse() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.submit(command())).isInstanceOf(ApiException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 실패는 닿지 못한 것으로 남는다")
    void otherFailuresRemainAsUnreachable() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.submit(command()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("상태 조회가 429 를 받아도 붐빈다고 적는다")
    void notesBusyWhenStatusQueryGets429() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.awaitCompletion(command(), "run-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BUSY);
    }

    @Test
    @DisplayName("중간에 끊긴 실행은 기다리지 않고 끝난 것으로 돌려준다")
    void returnsHalfwayCutRunAsEndedWithoutWaiting() {
        // v0.21.5 의 종료 상태다. 기다리면 실행 시간 한도(여기서는 2초)까지 조회만 되풀이한다.
        respondWith(200, "{\"run_id\":\"run-1\",\"status\":\"interrupted\",\"session_id\":\"sess-1\"}");

        HermesRunResult result = client.awaitCompletion(command(), "run-1");

        assertThat(result.status()).isEqualTo("interrupted");
        assertThat(result.succeeded()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    private HermesRunCommand command() {
        return new HermesRunCommand(
                "dad", baseUrl, "안녕", null, null, "openai-codex", "example-model", null);
    }
}
