package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청이 provider 와 모델을 함께 싣거나 함께 빼는 것, effort 를 {@code model_options} 로 싣는 것,
 * 막힘 판정 문자열을 본다.
 */
class HermesRunRequestTest {

    private final HermesProfileKeyStore keyStore = mock(HermesProfileKeyStore.class);
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final HttpHermesRunsClient client = new HttpHermesRunsClient(
            keyStore,
            new HermesProperties(
                    "keys",
                    "https://hermes-dashboard.example.com",
                    "test-dashboard-token",
                    "https://hermes-listener.example.com",
                    Duration.ofMillis(10),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)));

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        when(keyStore.resolve("dad")).thenReturn("dad-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/p/dad";
        // 받은 POST /v1/runs 본문을 저장하고 실행 번호를 돌려준다.
        server.createContext("/p/dad/v1/runs", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{\"run_id\":\"run-1\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void provider_와_모델이_모두_비면_세_키를_싣지_않는다() {
        String runId = client.submit(command(null, null, null));

        JsonNode body = submittedBody();
        assertThat(runId).isEqualTo("run-1");
        assertThat(body.has("provider")).isFalse();
        assertThat(body.has("model")).isFalse();
        assertThat(body.has("model_options")).isFalse();
        assertThat(body.path("input").asString()).isEqualTo("안녕");
    }

    @Test
    void provider_와_모델과_effort_를_주면_셋이_모두_실린다() {
        client.submit(command("openai-codex", "example-model", "high"));

        JsonNode body = submittedBody();
        assertThat(body.path("provider").asString()).isEqualTo("openai-codex");
        assertThat(body.path("model").asString()).isEqualTo("example-model");
        assertThat(body.path("model_options").path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(body.path("model_options").has("reasoning_effort")).isFalse();
    }

    @Test
    void effort_가_비어_있으면_model_options_를_싣지_않는다() {
        client.submit(command("openai-codex", "example-model", " "));

        assertThat(submittedBody().has("model_options")).isFalse();
    }

    @Test
    void provider_만_주면_Hermes_를_부르지_않고_실패한다() {
        assertThatThrownBy(() -> client.submit(command("openai-codex", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
        assertThat(receivedBody.get()).isNull();
    }

    private JsonNode submittedBody() {
        return JsonMapper.builder().build().readTree(receivedBody.get());
    }

    @Test
    void provider_가_비면_Hermes_를_부르지_않고_실패한다() {
        assertThatThrownBy(() -> client.submit(command(null, "example-model", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
    }

    @Test
    void 모델이_비면_Hermes_를_부르지_않고_실패한다() {
        assertThatThrownBy(() -> client.submit(command("openai-codex", " ", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
    }

    /**
     * 그 provider 의 계정이 전부 막혔을 때만 다음 순위로 넘어간다.
     *
     * <p>{@code HTTP 404} 와 {@code HTTP 402} 는 상류 provider 가 보낸 글이라 문구가 바뀔 수 있어
     * 판정에 쓰지 않는다.
     */
    @Test
    void 계정이_전부_막힌_실패만_넘김_대상이다() {
        assertThat(failed(HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " No Codex credentials stored.")
                        .providerBlocked())
                .isTrue();
        assertThat(failed("HTTP 404: 404 page not found").providerBlocked()).isFalse();
        assertThat(failed("HTTP 402: This request requires more credits").providerBlocked()).isFalse();
        assertThat(failed(null).providerBlocked()).isFalse();
    }

    @Test
    void 성공한_실행은_넘김_대상이_아니다() {
        HermesRunResult completed = new HermesRunResult(
                "run-1", "sess-1", "completed", "네", "example-model", "openai-codex",
                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " 남아 있는 글", TokenUsage.empty());

        assertThat(completed.providerBlocked()).isFalse();
    }

    private static HermesRunResult failed(String error) {
        return new HermesRunResult(
                "run-1", "sess-1", "failed", null, "example-model", "openai-codex", error, TokenUsage.empty());
    }

    private HermesRunCommand command(String provider, String model, String reasoningEffort) {
        return new HermesRunCommand(
                "dad", baseUrl, "안녕", null, null, provider, model, reasoningEffort);
    }
}
