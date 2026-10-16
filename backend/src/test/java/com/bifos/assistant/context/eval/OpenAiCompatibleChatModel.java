package com.bifos.assistant.context.eval;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI 호환 chat API({@code POST /chat/completions})를 직접 부른다. Hermes 를 거치지 않는다(ADR-20261015 / memory-eval-gate).
 *
 * <p>key 와 응답 본문은 예외 메시지에 넣지 않는다. 판정 결과가 흔들리지 않게 {@code temperature} 는 0 이다.
 */
final class OpenAiCompatibleChatModel implements ChatModel {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final String apiKey;
    private final int maxOutputTokens;


    OpenAiCompatibleChatModel(String baseUrl, String apiKey, int maxOutputTokens) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
    }


    @Override
    public String complete(String model, String system, String user) {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)),
            "max_tokens", maxOutputTokens,
            "temperature", 0);
        JsonNode response = restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + apiKey)
            .body(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, failed) -> {
                throw new IllegalStateException("chat 호출이 실패했다: HTTP " + failed.getStatusCode().value());
            })
            .body(JsonNode.class);
        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isString()) {
            throw new IllegalStateException("chat 응답의 모양이 다르다: choices[0].message.content 가 없다");
        }
        return content.asString();
    }
}
