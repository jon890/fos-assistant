package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ProfileModelDefaults;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** 최소한의 profile 기본값을 짧게 캐시한다. 조회 실패도 캐시해 반복 호출을 제한한다. */
@Component
public class ProfileModelDefaultsClient {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final RestClient client;
    private final String baseUrl;
    private final String token;
    private final Clock clock = Clock.systemUTC();

    private record Cached(Instant expiresAt, ProfileModelDefaults value) {
    }

    public ProfileModelDefaultsClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
        baseUrl = properties.dashboardBaseUrl().replaceAll("/+$", "");
        token = properties.dashboardToken();
    }

    public ProfileModelDefaults read(String profile) {
        if (!HermesProfileName.isValid(profile)) {
            return null;
        }
        Instant now = clock.instant();
        Cached known = cache.get(profile);
        if (known != null && now.isBefore(known.expiresAt())) {
            return known.value();
        }
        ProfileModelDefaults defaults = null;
        try {
            JsonNode row = client.get().uri(baseUrl + "/api/profiles/{profile}/model-defaults", profile)
                    .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
            if (row != null && row.isObject() && row.has("reasoningEffort")) {
                defaults = new ProfileModelDefaults(text(row, "provider"), text(row, "model"),
                        text(row, "reasoningEffort"));
            }
        } catch (RuntimeException ignored) {
            // 기본값 조회 실패는 답이나 실제 모델 기록을 실패시키지 않는다.
        }
        cache.put(profile, new Cached(now.plus(TTL), defaults));
        return defaults;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isString() && !value.asText().isBlank() ? value.asText() : null;
    }
}
