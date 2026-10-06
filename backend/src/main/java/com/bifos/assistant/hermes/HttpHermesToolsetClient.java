package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Hermes 대시보드와 listener에서 API 실행용 toolset을 읽고 쓴다. */
@Component
@Slf4j
public class HttpHermesToolsetClient implements HermesToolsetClient {

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final String dashboardBaseUrl;
    private final String dashboardToken;

    public HttpHermesToolsetClient(HermesProfileKeyStore keyStore, HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
        this.dashboardBaseUrl = stripTrailingSlash(properties.dashboardBaseUrl());
        this.dashboardToken = properties.dashboardToken();
    }

    @Override
    public List<ToolsetCatalogEntry> readCatalog() {
        try {
            JsonNode response = restClient
                    .get()
                    .uri(dashboardBaseUrl + "/api/tools/toolsets")
                    .header("Authorization", "Bearer " + dashboardToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            List<ToolsetCatalogEntry> result = new ArrayList<>();
            // 대시보드 `GET /api/tools/toolsets` 는 항목 배열을 그대로 돌려준다.
            for (JsonNode entry : entries(response)) {
                String name = text(entry, "name");
                if (name == null) {
                    throw malformedResponse();
                }
                result.add(new ToolsetCatalogEntry(name, text(entry, "label"), text(entry, "description")));
            }
            return List.copyOf(result);
        } catch (RestClientException ex) {
            log.warn("Hermes toolset 목록을 읽지 못했다", ex);
            throw HermesCallFailure.of(ex, "could not read Hermes toolsets");
        }
    }

    @Override
    public List<String> readEnabled(String apiBaseUrl, String profileName) {
        try {
            JsonNode response = restClient
                    .get()
                    .uri(stripTrailingSlash(apiBaseUrl) + "/v1/toolsets")
                    .header("Authorization", "Bearer " + keyStore.resolve(profileName))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            // listener `GET /v1/toolsets` 는 `{"object": "list", "platform": "api_server", "data": [...]}` 로 감싼다.
            List<String> enabled = new ArrayList<>();
            for (JsonNode entry : entries(response == null ? null : response.get("data"))) {
                String name = text(entry, "name");
                JsonNode enabledValue = entry.get("enabled");
                if (name == null || enabledValue == null || !enabledValue.isBoolean()) {
                    throw malformedResponse();
                }
                if (enabledValue.asBoolean()) {
                    enabled.add(name);
                }
            }
            return List.copyOf(enabled);
        } catch (RestClientException ex) {
            log.warn("Hermes API 실행 toolset을 읽지 못했다 profile={}", profileName, ex);
            throw HermesCallFailure.of(ex, "could not read enabled Hermes toolsets");
        }
    }

    @Override
    public void writeApiServer(String profileName, List<String> toolsets, String sandboxOwner) {
        try {
            restClient
                    .put()
                    .uri(dashboardBaseUrl + "/api/config")
                    .header("Authorization", "Bearer " + dashboardToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "profile",
                            profileName,
                            "config",
                            Map.of("platform_toolsets", Map.of("api_server", toolsets)),
                            "sandbox_owner",
                            sandboxOwner))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Hermes API 실행 toolset을 쓰지 못했다 profile={}", profileName, ex);
            Optional<HermesRequestRejected> sandboxRejection = HermesCallFailure.sandboxRejection(ex);
            if (sandboxRejection.isPresent()) {
                throw sandboxRejection.get();
            }
            throw HermesCallFailure.of(ex, "could not write Hermes toolsets");
        }
    }

    private static Iterable<JsonNode> entries(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw malformedResponse();
        }
        List<JsonNode> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static ApiException malformedResponse() {
        return new ApiException(ErrorCode.HERMES_UNAVAILABLE, "Hermes returned an invalid toolset response");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
