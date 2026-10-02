package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.SubagentProviderLookup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 자식 session 의 provider 를 대시보드 plugin 에서 읽는다. 근거는 ADR-067 에 있다.
 *
 * <p>자식마다 한 번 읽는 값이라 캐시하지 않는다. 예외를 던지지 않고, 다시 읽을 만한 실패만
 * {@link SubagentProviderLookup#unreachable()} 로 구분한다.
 */
@Component
@Slf4j
public class SubagentProviderClient {
    private final RestClient client;
    private final String baseUrl;
    private final String token;

    public SubagentProviderClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
        baseUrl = properties.dashboardBaseUrl().replaceAll("/+$", "");
        token = properties.dashboardToken();
    }

    public SubagentProviderLookup read(String profile, String sessionId) {
        if (!HermesProfileName.isValid(profile) || sessionId == null || sessionId.isBlank()) {
            return SubagentProviderLookup.absent();
        }
        try {
            JsonNode row = client.get()
                    .uri(baseUrl + "/api/profiles/{profile}/sessions/{sessionId}/provider", profile, sessionId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JsonNode.class);
            if (row == null || !row.isObject()) {
                // 200 인데 약속한 모양이 아니면 읽지 못한 본문으로 본다.
                log.warn("자식 session 의 provider 응답이 객체가 아니다 profile={}", profile);
                return SubagentProviderLookup.unreachable();
            }
            String provider = text(row, "provider");
            return provider == null
                    ? SubagentProviderLookup.absent()
                    : SubagentProviderLookup.found(provider, text(row, "model"));
        } catch (HttpClientErrorException ex) {
            // 4xx 는 다시 읽어도 같은 답이다. 옛 plugin 의 401 을 기다리면 자식의 토큰이 합계에 들어가지 못한다.
            if (ex.getStatusCode().value() != 404) {
                log.warn(
                        "자식 session 의 provider 조회가 거절됐다 status={} profile={}",
                        ex.getStatusCode().value(),
                        profile);
            }
            return SubagentProviderLookup.absent();
        } catch (RuntimeException ex) {
            // 5xx, 연결 실패, 시간 초과, 읽지 못한 본문이다. 잠시 뒤 다시 읽을 수 있다.
            // 예외 본문에는 주소와 응답 본문이 섞일 수 있어 종류만 남긴다.
            log.warn(
                    "자식 session 의 provider 를 읽지 못했다 error={} profile={}",
                    ex.getClass().getSimpleName(),
                    profile);
            return SubagentProviderLookup.unreachable();
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isString() && !value.asText().isBlank() ? value.asText() : null;
    }
}
