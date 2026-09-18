package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
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
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @Test
    void 실행_제출이_429_를_받으면_붐빈다고_적는다() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.submit(command()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BUSY);
    }

    @Test
    void 붐빈다는_응답에_다시_보내지_않는다() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.submit(command())).isInstanceOf(ApiException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 다른_실패는_닿지_못한_것으로_남는다() {
        respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.submit(command()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    void 상태_조회가_429_를_받아도_붐빈다고_적는다() {
        respondWith(429, RATE_LIMITED);

        assertThatThrownBy(() -> client.awaitCompletion(command(), "run-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_BUSY);
    }

    private HermesRunCommand command() {
        return new HermesRunCommand(
                "dad", baseUrl, "안녕", null, null, "openai-codex", "gpt-5.5");
    }
}
