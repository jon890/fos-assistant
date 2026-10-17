package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.port.EmbeddingClient;
import com.bifos.assistant.memory.application.port.EmbeddingRejectedException;
import com.bifos.assistant.memory.application.port.EmbeddingUnavailableException;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 호환 {@code POST {baseUrl}/v1/embeddings} 를 부른다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>요청은 {@code {"model", "input": [...]}} 이고 답의 {@code data[].embedding} 을 {@code data[].index} 차례로 맞춰 낸다. 개수가 다르거나 차원이
 * 섞이면 실패다. 호출마다 그 호출의 timeout 을 응답 대기 상한으로 쓴다. 예외 메시지와 로그에 기억 글을 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final MemoryEmbeddingProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();


    @Override
    public List<float[]> embed(List<String> inputs, Duration timeout) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(properties.baseUrl()) + "/v1/embeddings"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("model", properties.model(), "input", inputs))))
                .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            throw new EmbeddingUnavailableException("embedding request timed out", ex);
        } catch (IOException ex) {
            throw new EmbeddingUnavailableException("embedding request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("embedding request was interrupted", ex);
        } catch (IllegalArgumentException ex) {
            throw new EmbeddingUnavailableException("embedding endpoint is invalid", ex);
        }
        if (response.statusCode() / 100 == 4) {
            throw new EmbeddingRejectedException("embedding service rejected the request with status " + response.statusCode());
        }
        if (response.statusCode() / 100 != 2) {
            throw new EmbeddingUnavailableException("embedding service answered status " + response.statusCode());
        }
        return vectorsOf(response.body(), inputs.size());
    }


    private static List<float[]> vectorsOf(String body, int expected) {
        JsonNode data;
        try {
            data = JSON.readTree(body).path("data");
        } catch (JacksonException ex) {
            throw new EmbeddingUnavailableException("embedding response is not JSON", ex);
        }
        if (!data.isArray() || data.size() != expected) {
            throw new EmbeddingUnavailableException("embedding response has a different count");
        }
        float[][] ordered = new float[expected][];
        for (JsonNode each : data) {
            JsonNode index = each.path("index");
            JsonNode embedding = each.path("embedding");
            if (!index.isIntegralNumber() || index.intValue() < 0 || index.intValue() >= expected
                || ordered[index.intValue()] != null || !embedding.isArray() || embedding.isEmpty()) {
                throw new EmbeddingUnavailableException("embedding response has an invalid item");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                if (!embedding.get(i).isNumber()) {
                    throw new EmbeddingUnavailableException("embedding response has a non-number value");
                }
                vector[i] = embedding.get(i).floatValue();
            }
            ordered[index.intValue()] = vector;
        }
        List<float[]> vectors = new ArrayList<>(List.of(ordered));
        int dimensions = vectors.getFirst().length;
        if (vectors.stream().anyMatch(vector -> vector.length != dimensions)) {
            throw new EmbeddingUnavailableException("embedding response mixes dimensions");
        }
        return vectors;
    }


    private static String stripSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
