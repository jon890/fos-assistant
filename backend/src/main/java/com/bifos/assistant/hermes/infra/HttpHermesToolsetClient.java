package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.port.HermesRequestRejected;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Hermes 의 「그림 만들기」 toolset 이름이다. {@code hermes} 가 {@code agent} 보다 아래라 그쪽 상수를 쓰지 못한다. */
    private static final String IMAGE_TOOLSET = "image_gen";
    private static final String WEB_TOOLSET = "web";
    /** fos-ctx 의 상품 페이지 도구가 든 toolset 이다. {@code web} 을 쓰는 목록에만 둔다. */
    private static final String PRODUCT_PAGE_TOOLSET = "fos-product-page";

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final String dashboardBaseUrl;
    private final String dashboardToken;
    private final SandboxAttachmentDirectory attachmentDirectory;


    public HttpHermesToolsetClient(
        HermesProfileKeyStore keyStore,
        HermesProperties properties,
        SandboxAttachmentDirectory attachmentDirectory) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
        this.dashboardBaseUrl = stripTrailingSlash(properties.dashboardBaseUrl());
        this.dashboardToken = properties.dashboardToken();
        this.attachmentDirectory = attachmentDirectory;
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
        writeConfig(profileName, toolsets, sandboxOwner, false);
    }


    @Override
    public void writeApiServerInSandbox(String profileName, List<String> toolsets, String sandboxOwner) {
        writeConfig(profileName, toolsets, sandboxOwner, true);
    }


    private void writeConfig(String profileName, List<String> requested, String sandboxOwner, boolean requireSandbox) {
        List<String> toolsets = withProductPage(requested);
        attachmentDirectory.ensure(sandboxOwner);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profileName);
        body.put("config", Map.of("platform_toolsets", Map.of("api_server", toolsets)));
        body.put("sandbox_owner", sandboxOwner);
        // 참일 때만 싣는다. 이 칸을 모르는 옛 plugin 이 사람의 도구 저장까지 400 으로 거절하지 않게 한다.
        if (requireSandbox) {
            body.put("require_sandbox", true);
        }
        // 「그림 만들기」 를 켜는 저장에만 넣는다. plugin 이 같은 쓰기에서 profile 에 이미지 서비스를 넣는다.
        // 이 칸을 모르는 옛 plugin 은 400 으로 거절해, 서비스 없이 도구만 켜진 채 남지 않는다(ADR-20261119 / image-gen-defaults).
        if (toolsets.contains(IMAGE_TOOLSET)) {
            body.put("ensure_image_service", true);
        }
        try {
            restClient
                .put()
                .uri(dashboardBaseUrl + "/api/config")
                .header("Authorization", "Bearer " + dashboardToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
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


    /**
     * 상품 페이지 toolset 을 {@code web} 에 맞춘다. {@code web} 이 있으면 더하고 없으면 뺀다.
     *
     * <p>Hermes 는 plugin toolset 을 그 profile 의 {@code known_plugin_toolsets} 에 없으면 목록과 상관없이 켠다. profile 틀과 대시보드
     * plugin 이 이 이름을 그 기록에 적어 두므로, 목록에 있는지가 켜짐을 정한다(hermes-contract 의 「plugin toolset 이름」). 모든 쓰기가 이
     * 메서드를 지나서, 웹 검색을 허락하지 않은 profile 에 상품 페이지 도구가 켜지지 않는다.
     */
    private static List<String> withProductPage(List<String> toolsets) {
        List<String> result = new ArrayList<>(toolsets);
        result.remove(PRODUCT_PAGE_TOOLSET);
        if (result.contains(WEB_TOOLSET)) {
            result.add(PRODUCT_PAGE_TOOLSET);
        }
        return List.copyOf(result);
    }
}
