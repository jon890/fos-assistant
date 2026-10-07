package com.bifos.assistant.hermes;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** 도구·MCP·기억을 차단한 판단 profile 인지 매번 확인한다. 실패는 준비되지 않은 것으로 본다. */
@Component
@RequiredArgsConstructor
public class DecisionProfileReadinessClient {

    private final HermesProperties properties;

    public boolean ready(String profile) {
        if (!HermesProfileName.isValid(profile)) {
            return false;
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(properties.connectTimeout());
            factory.setReadTimeout(properties.readTimeout());
            RestClient client = RestClient.builder().requestFactory(factory).build();
            String baseUrl = properties.dashboardBaseUrl().replaceAll("/+$", "");
            JsonNode result = client.get().uri(baseUrl + "/api/profiles/{profile}/decision-readiness", profile)
                    .header("Authorization", "Bearer " + properties.dashboardToken()).retrieve().body(JsonNode.class);
            return result != null && result.path("version").isInt() && result.path("version").asInt() == 1
                    && result.path("ready").isBoolean() && result.path("ready").asBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
