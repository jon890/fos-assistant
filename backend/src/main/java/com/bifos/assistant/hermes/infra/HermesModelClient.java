package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.ReasoningCapability;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class HermesModelClient {

    /**
     * {@code refresh=true} 의 읽기 timeout 이다. Hermes 가 provider 마다 실제 조회를 기다리고 다시 채우므로
     * 평소 읽기보다 오래 걸린다(Codex 조회만 10초).
     */
    private static final Duration REFRESH_READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final RestClient refreshClient;
    private final HermesProfileKeyStore keyStore;


    public HermesModelClient(HermesProfileKeyStore keyStore, HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        SimpleClientHttpRequestFactory refreshFactory = new SimpleClientHttpRequestFactory();
        refreshFactory.setConnectTimeout(properties.connectTimeout());
        refreshFactory.setReadTimeout(
            properties.readTimeout().compareTo(REFRESH_READ_TIMEOUT) > 0
                ? properties.readTimeout()
                : REFRESH_READ_TIMEOUT);
        this.refreshClient = RestClient.builder().requestFactory(refreshFactory).build();
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
        return readCatalog(apiBaseUrl, profileName, false);
    }


    /**
     * @param refresh 참이면 {@code refresh=true} 를 실어 Hermes 의 디스크 캐시를 건너뛰고 실제 조회를 기다린다.
     * 평소 읽기({@code false})는 캐시만 읽어, 자격 증명이 바뀐 직후 첫 요청이 고정 대체 목록을 받을 수 있다
     */
    public HermesModelCatalog readCatalog(String apiBaseUrl, String profileName, boolean refresh) {
        String apiKey = keyStore.resolve(profileName);
        JsonNode response;
        try {
            response = (refresh ? refreshClient : restClient)
                .get()
                .uri(stripTrailingSlash(apiBaseUrl) + "/api/model/options" + (refresh ? "?refresh=true" : ""))
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
        if (slug == null
            || authenticated == null
            || !authenticated.asBoolean(false)
            || modelRows == null
            || !modelRows.isArray()) {
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
        // 모든 모델에 항목을 만든다. Hermes 가 밝히지 않은 칸은 UNKNOWN 으로 두고 참으로 채우지 않는다(ADR-060).
        Map<String, ReasoningCapability> reasoning = new LinkedHashMap<>();
        JsonNode capabilities = row.get("capabilities");
        boolean hasCapabilities = capabilities != null && capabilities.isObject();
        for (String model : models) {
            if (!hasCapabilities) {
                reasoning.put(model, ReasoningCapability.UNKNOWN_ALL);
                continue;
            }
            JsonNode modelCapabilities = capabilities.path(model);
            reasoning.put(
                model,
                new ReasoningCapability(
                    supportOf(modelCapabilities.get("reasoning")),
                    supportOf(modelCapabilities.get("can_disable_reasoning"))));
        }
        String name = text(row, "name");
        return new HermesModelCatalog.Provider(
            slug, name == null ? slug : name, List.copyOf(models), Collections.unmodifiableMap(reasoning));
    }


    /** boolean 칸이면 참은 {@code SUPPORTED}, 거짓은 {@code UNSUPPORTED} 다. 칸이 없거나 boolean 이 아니면 {@code UNKNOWN} 이다. */
    private static ReasoningCapability.Support supportOf(JsonNode flag) {
        if (flag == null || !flag.isBoolean()) {
            return ReasoningCapability.Support.UNKNOWN;
        }
        return flag.asBoolean() ? ReasoningCapability.Support.SUPPORTED : ReasoningCapability.Support.UNSUPPORTED;
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
