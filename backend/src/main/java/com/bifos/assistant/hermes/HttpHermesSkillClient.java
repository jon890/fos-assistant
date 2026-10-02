package com.bifos.assistant.hermes;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Hermes 대시보드의 스킬 경로를 HTTP 로 부른다. 주소와 토큰은 설정으로만 받는다. */
@Component
@Slf4j
public class HttpHermesSkillClient implements HermesSkillClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String token;

    public HttpHermesSkillClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = stripTrailingSlash(properties.dashboardBaseUrl());
        this.token = properties.dashboardToken();
    }

    @Override
    public List<HermesSkill> list(String profile) {
        requireValidProfileName(profile);
        try {
            JsonNode response = restClient
                    .get()
                    .uri(baseUrl + "/api/skills?profile={profile}", profile)
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            // 대시보드 `GET /api/skills` 는 항목 배열을 그대로 돌려준다. 스킬이 없는 profile 은 빈 배열이다.
            if (response == null || !response.isArray()) {
                throw malformedResponse();
            }
            List<HermesSkill> result = new ArrayList<>();
            for (JsonNode entry : response) {
                String name = text(entry, "name");
                if (name == null) {
                    throw malformedResponse();
                }
                JsonNode enabled = entry.get("enabled");
                String description = text(entry, "description");
                result.add(new HermesSkill(
                        name,
                        description == null ? "" : description,
                        enabled == null || !enabled.isBoolean() || enabled.asBoolean()));
            }
            return List.copyOf(result);
        } catch (RestClientException ex) {
            log.warn("Hermes 스킬 목록을 읽지 못했다 profile={}", profile, ex);
            throw HermesCallFailure.of(ex, "could not read Hermes skills");
        }
    }

    @Override
    public void toggle(String profile, String name, boolean enabled) {
        requireValidProfileName(profile);
        try {
            restClient
                    .put()
                    .uri(baseUrl + "/api/skills/toggle")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("profile", profile, "name", name, "enabled", enabled))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Hermes 스킬을 켜거나 끄지 못했다 profile={} skill={}", profile, name, ex);
            throw HermesCallFailure.ofDistinguishingRejection(ex, "could not toggle the Hermes skill");
        }
    }

    @Override
    public void publish(String profile, List<String> externalDirs, List<String> apiServerToolsets) {
        requireValidProfileName(profile);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("skills", Map.of("external_dirs", List.copyOf(externalDirs)));
        if (apiServerToolsets != null) {
            config.put("platform_toolsets", Map.of("api_server", List.copyOf(apiServerToolsets)));
        }
        try {
            restClient
                    .put()
                    .uri(baseUrl + "/api/config")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("profile", profile, "config", config))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Hermes 스킬 경로를 게시하지 못했다 profile={} dirs={}", profile, externalDirs.size(), ex);
            throw HermesCallFailure.ofDistinguishingRejection(ex, "could not publish the skill directory");
        }
    }

    private static void requireValidProfileName(String profile) {
        if (!HermesProfileName.isValid(profile)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "profile name is not a valid Hermes profile");
        }
    }

    private static ApiException malformedResponse() {
        return new ApiException(ErrorCode.HERMES_UNAVAILABLE, "Hermes returned an invalid skill response");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
