package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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

    /**
     * 그 profile 로 고를 수 있는 provider 와 모델을 읽는다.
     *
     * <p>Hermes 는 설정하지 않은 provider 도 {@code authenticated: false}, {@code models: []} 행으로
     * 준다. 그런 행은 고를 수 없으므로 뺀다. 응답 코드가 무엇이든 Hermes 가 답하지 못하면 {@code
     * HERMES_UNAVAILABLE} 이다. 429 를 따로 나누지 않는 것은 실행의 동시 한도와 이 조회가 관계가 없기
     * 때문이다. key 가 없을 때의 예외는 그대로 올린다.
     */
    public HermesModelCatalog readCatalog(String apiBaseUrl, String profileName) {
        String apiKey = keyStore.resolve(profileName);
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(stripTrailingSlash(apiBaseUrl) + "/api/model/options")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("Hermes 의 모델 목록을 읽지 못했다 profile={}", profileName, ex);
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "cannot read the model list from Hermes", ex);
        }
        List<HermesModelCatalog.Provider> providers = new ArrayList<>();
        JsonNode rows = response == null ? null : response.get("providers");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                HermesModelCatalog.Provider provider = providerOf(row);
                if (provider != null) {
                    providers.add(provider);
                }
            }
        }
        return new HermesModelCatalog(text(response, "provider"), text(response, "model"), List.copyOf(providers));
    }

    /** 고를 수 없는 행이면 null 이다. */
    private static HermesModelCatalog.Provider providerOf(JsonNode row) {
        String slug = text(row, "slug");
        JsonNode authenticated = row.get("authenticated");
        JsonNode modelRows = row.get("models");
        if (slug == null || authenticated == null || !authenticated.asBoolean(false)
                || modelRows == null || !modelRows.isArray()) {
            return null;
        }
        List<String> models = new ArrayList<>();
        for (JsonNode model : modelRows) {
            if (model.isTextual() && !model.asText().isBlank()) {
                models.add(model.asText());
            }
        }
        if (models.isEmpty()) {
            return null;
        }
        Map<String, Boolean> reasoning = new LinkedHashMap<>();
        JsonNode capabilities = row.get("capabilities");
        if (capabilities != null && capabilities.isObject()) {
            for (String model : models) {
                JsonNode flag = capabilities.path(model).get("reasoning");
                if (flag != null && flag.isBoolean()) {
                    reasoning.put(model, flag.asBoolean());
                }
            }
        }
        String name = text(row, "name");
        return new HermesModelCatalog.Provider(
                slug, name == null ? slug : name, List.copyOf(models), Map.copyOf(reasoning));
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
