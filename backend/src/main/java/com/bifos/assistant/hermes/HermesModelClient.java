package com.bifos.assistant.hermes;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
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

    public String readModel(String apiBaseUrl, String profileName) {
        try {
            String apiKey = keyStore.resolve(profileName);
            JsonNode response = restClient.get()
                    .uri(stripTrailingSlash(apiBaseUrl) + "/api/model/options")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(response)
                    .map(node -> node.get("model"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .filter(model -> !model.isBlank())
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("could not read Hermes model profile={}", profileName, ex);
            return null;
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
