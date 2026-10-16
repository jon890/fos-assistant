package com.bifos.assistant.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.embedding.EmbeddingUnavailableException;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.embedding.OpenAiCompatibleEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleEmbeddingClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;


    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }


    @Test
    @DisplayName("모델과 글을 보내고 index 가 뒤섞인 답을 넣은 순서대로 맞춘다")
    void sendsModelAndInputsAndOrdersByIndex() throws IOException {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        OpenAiCompatibleEmbeddingClient client = clientAnswering(200, """
            {"data":[{"index":1,"embedding":[0.5,0.25]},{"index":0,"embedding":[1.0,-1.0]}]}
            """, received, path, Duration.ZERO);

        List<float[]> vectors = client.embed(List.of("첫 글", "둘째 글"), Duration.ofSeconds(2));

        assertThat(path.get()).isEqualTo("/v1/embeddings");
        assertThat(received.get().path("model").asString()).isEqualTo("test-model");
        assertThat(received.get().path("input")).extracting(JsonNode::asString).containsExactly("첫 글", "둘째 글");
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, -1.0f);
        assertThat(vectors.get(1)).containsExactly(0.5f, 0.25f);
    }


    @Test
    @DisplayName("500 답과 개수가 다른 답과 차원이 섞인 답과 늦은 답은 모두 임베딩 실패다")
    void failsOnErrorStatusCountMismatchMixedDimensionsAndTimeout() throws IOException {
        assertUnavailable(clientAnswering(500, "{}", null, null, Duration.ZERO), Duration.ofSeconds(2));
        tearDown();
        assertUnavailable(
            clientAnswering(200, "{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}", null, null, Duration.ZERO), Duration.ofSeconds(2));
        tearDown();
        assertUnavailable(
            clientAnswering(
                200, "{\"data\":[{\"index\":0,\"embedding\":[1.0]},{\"index\":1,\"embedding\":[1.0,2.0]}]}", null, null, Duration.ZERO),
            Duration.ofSeconds(2));
        tearDown();
        assertUnavailable(clientAnswering(200, "not json", null, null, Duration.ZERO), Duration.ofSeconds(2));
        tearDown();
        assertUnavailable(
            clientAnswering(
                200, "{\"data\":[{\"index\":0,\"embedding\":[1.0]},{\"index\":1,\"embedding\":[2.0]}]}", null, null, Duration.ofMillis(800)),
            Duration.ofMillis(200));
    }


    @Test
    @DisplayName("글이 없으면 부르지 않고 빈 목록이다")
    void returnsEmptyWithoutCalling() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
            new MemoryEmbeddingProperties("http://127.0.0.1:1", "test-model", null, null, null, null, null, null));

        assertThat(client.embed(List.of(), Duration.ofSeconds(1))).isEmpty();
    }


    @Test
    @DisplayName("http 나 https 가 아닌 주소는 기동에서 거절하고 빈 주소는 꺼짐이다")
    void rejectsMalformedBaseUrlAtStartup() {
        assertThatThrownBy(() -> new MemoryEmbeddingProperties("embedding.test:8080", "test-model", null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("embedding.test");
        assertThatThrownBy(() -> new MemoryEmbeddingProperties("ftp://embedding.test", "test-model", null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MemoryEmbeddingProperties("", "test-model", null, null, null, null, null, null).enabled()).isFalse();
        assertThat(new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, null, null).enabled()).isTrue();
    }


    private static void assertUnavailable(OpenAiCompatibleEmbeddingClient client, Duration timeout) {
        assertThatThrownBy(() -> client.embed(List.of("첫 글", "둘째 글"), timeout))
            .isInstanceOf(EmbeddingUnavailableException.class)
            .hasMessageNotContaining("첫 글");
    }


    private OpenAiCompatibleEmbeddingClient clientAnswering(
        int status, String body, AtomicReference<JsonNode> received, AtomicReference<String> path, Duration delay) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (received != null) {
                received.set(JSON.readTree(request));
            }
            if (path != null) {
                path.set(exchange.getRequestURI().getPath());
            }
            sleep(delay);
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            } catch (IOException ignored) {
                // 늦은 답을 기다리지 않고 끊은 쪽이 있으면 쓰기가 실패한다
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new OpenAiCompatibleEmbeddingClient(
            new MemoryEmbeddingProperties(baseUrl, "test-model", null, null, null, null, null, null));
    }


    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
