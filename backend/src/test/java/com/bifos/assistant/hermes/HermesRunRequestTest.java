package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.dto.HermesImage;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
    @DisplayName("provider 와 모델이 모두 비면 세 키를 싣지 않는다")
    void sendsNoThreeKeysWhenProviderAndModelAreBothBlank() {
        String runId = client.submit(command(null, null, null));

        JsonNode body = submittedBody();
        assertThat(runId).isEqualTo("run-1");
        assertThat(body.has("provider")).isFalse();
        assertThat(body.has("model")).isFalse();
        assertThat(body.has("model_options")).isFalse();
        assertThat(body.path("input").asString()).isEqualTo("안녕");
    }

    @Test
    @DisplayName("사진이 있으면 input 은 user 메시지 목록이고 글, 이름표, 이미지 순이다")
    void sendsListInputWithTextLabelAndImageWhenImagesGiven() {
        String dataUrl = "data:image/jpeg;base64,AAAA";
        client.submit(new HermesRunCommand(
                "dad",
                baseUrl,
                "안녕",
                null,
                null,
                null,
                null,
                null,
                List.of(new HermesImage("1번째 사진", dataUrl))));

        JsonNode input = submittedBody().path("input");
        assertThat(input.isArray()).as("input: %s", input).isTrue();
        JsonNode last = input.get(input.size() - 1);
        assertThat(last.path("role").asString()).isEqualTo("user");
        JsonNode content = last.path("content");
        assertThat(content.size()).as("content: %s", content).isEqualTo(3);
        assertThat(content.get(0).path("type").asString()).isEqualTo("text");
        assertThat(content.get(0).path("text").asString()).isEqualTo("안녕");
        assertThat(content.get(1).path("type").asString()).isEqualTo("text");
        assertThat(content.get(1).path("text").asString()).isEqualTo("1번째 사진");
        assertThat(content.get(2).path("type").asString()).isEqualTo("image_url");
        assertThat(content.get(2).path("image_url").path("url").asString()).isEqualTo(dataUrl);
        assertThat(content.get(2).path("image_url").has("detail")).isFalse();
    }

    @Test
    @DisplayName("provider 와 모델과 effort 를 주면 셋이 모두 실린다")
    void sendsAllThreeWhenProviderModelAndEffortGiven() {
        client.submit(command("openai-codex", "example-model", "high"));

        JsonNode body = submittedBody();
        assertThat(body.path("provider").asString()).isEqualTo("openai-codex");
        assertThat(body.path("model").asString()).isEqualTo("example-model");
        assertThat(body.path("model_options").path("reasoning").path("effort").asString())
                .isEqualTo("high");
        assertThat(body.path("model_options").has("reasoning_effort")).isFalse();
    }

    @Test
    @DisplayName("effort 가 비어 있으면 model options 를 싣지 않는다")
    void sendsNoModelOptionsWhenEffortIsBlank() {
        client.submit(command("openai-codex", "example-model", " "));

        assertThat(submittedBody().has("model_options")).isFalse();
    }

    @Test
    @DisplayName("effort none 은 model options 에 그대로 싣고 null 은 model options 를 빼 둘을 다르게 보낸다")
    void sendsNoneAsEffortButLeavesModelOptionsOutForNull() {
        client.submit(command("openai-codex", "example-model", "none"));
        JsonNode disabled = submittedBody();

        client.submit(command("openai-codex", "example-model", null));
        JsonNode unspecified = submittedBody();

        assertThat(disabled.path("model_options")
                        .path("reasoning")
                        .path("effort")
                        .asString())
                .isEqualTo("none");
        assertThat(unspecified.has("model_options")).isFalse();
    }

    @Test
    @DisplayName("provider 만 주면 Hermes 를 부르지 않고 실패한다")
    void failsWithoutCallingHermesWhenOnlyProviderGiven() {
        assertThatThrownBy(() -> client.submit(command("openai-codex", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
        assertThat(receivedBody.get()).isNull();
    }

    @Test
    @DisplayName("provider 를 바꿔도 profile key 는 같고 모델과 effort 는 요청값 그대로 보낸다")
    void keepsProfileKeyAndRequestedEffortWhenProviderChanges() {
        for (String provider : new String[] {"openai-codex", "anthropic", "openrouter", "custom-provider"}) {
            client.submit(command(provider, "shared-model", "max"));

            JsonNode body = submittedBody();
            assertThat(body.path("provider").asString()).isEqualTo(provider);
            assertThat(body.path("model").asString()).isEqualTo("shared-model");
            assertThat(body.path("model_options")
                            .path("reasoning")
                            .path("effort")
                            .asString())
                    .isEqualTo("max");
            assertThat(receivedAuthorization.get()).isEqualTo("Bearer dad-key");
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("input", "provider", "model", "model_options");
        }
    }

    private JsonNode submittedBody() {
        return JsonMapper.builder().build().readTree(receivedBody.get());
    }

    @Test
    @DisplayName("provider 가 비면 Hermes 를 부르지 않고 실패한다")
    void failsWithoutCallingHermesWhenProviderBlank() {
        assertThatThrownBy(() -> client.submit(command(null, "example-model", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(keyStore);
    }

    @Test
    @DisplayName("모델이 비면 Hermes 를 부르지 않고 실패한다")
    void failsWithoutCallingHermesWhenModelBlank() {
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
    @DisplayName("계정이 전부 막힌 실패만 넘김 대상이다")
    void onlyFailuresWithAllAccountsBlockedAreFallbackTargets() {
        assertThat(failed(HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " No Codex credentials stored.")
                        .providerBlocked())
                .isTrue();
        assertThat(failed("HTTP 404: 404 page not found").providerBlocked()).isFalse();
        assertThat(failed("HTTP 402: This request requires more credits").providerBlocked())
                .isFalse();
        assertThat(failed(null).providerBlocked()).isFalse();
    }

    @Test
    @DisplayName("성공한 실행은 넘김 대상이 아니다")
    void succeededRunIsNotFallbackTarget() {
        HermesRunResult completed = new HermesRunResult(
                "run-1",
                "sess-1",
                "completed",
                "네",
                "example-model",
                "openai-codex",
                HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " 남아 있는 글",
                TokenUsage.empty());

        assertThat(completed.providerBlocked()).isFalse();
    }

    private static HermesRunResult failed(String error) {
        return new HermesRunResult(
                "run-1", "sess-1", "failed", null, "example-model", "openai-codex", error, TokenUsage.empty());
    }

    private HermesRunCommand command(String provider, String model, String reasoningEffort) {
        return new HermesRunCommand("dad", baseUrl, "안녕", null, null, provider, model, reasoningEffort);
    }
}
