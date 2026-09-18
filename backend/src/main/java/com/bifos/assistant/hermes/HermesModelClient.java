package com.bifos.assistant.hermes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.bifos.assistant.hermes.dto.HermesModelOptions;
import tools.jackson.databind.JsonNode;

@Component
public class HermesModelClient {
    private static final Logger log = LoggerFactory.getLogger(HermesModelClient.class);

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;

    public HermesModelClient(HermesProfileKeyStore keyStore, HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
    }

    /** 그 profile 의 기본 모델만 읽는다. 읽지 못하면 null 이다. */
    public String readModel(String apiBaseUrl, String profileName) {
        HermesModelOptions options = readOptions(apiBaseUrl, profileName);
        return options == null ? null : options.model();
    }

    /**
     * 그 profile 의 기본 provider 와 모델을 읽는다. 읽지 못하면 null 이다.
     *
     * <p>{@code provider} 를 주지 않는 Hermes 판이 있어 그 칸은 비어 있을 수 있다. 비면 부르는 쪽이
     * provider 를 건드리지 않는다.
     */
    public HermesModelOptions readOptions(String apiBaseUrl, String profileName) {
        try {
            String apiKey = keyStore.resolve(profileName);
            JsonNode response = restClient.get()
                    .uri(stripTrailingSlash(apiBaseUrl) + "/api/model/options")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            String model = text(response, "model");
            if (model == null) {
                return null;
            }
            return new HermesModelOptions(model, text(response, "provider"));
        } catch (RuntimeException ex) {
            log.warn("Hermes 의 모델 설정을 읽지 못했다 profile={}", profileName, ex);
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
