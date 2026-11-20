package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.port.HermesGatewayClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 대시보드 plugin 의 {@code GET /api/gateway} 를 부른다(ADR-20261120 / connector-auto-apply).
 *
 * <p>404 를 포함한 실패와 모양이 틀린 답은 모두 기동 시각을 모르는 것으로 읽는다. 로그는 부르는 쪽이 상태가 바뀔 때만 남기므로 여기서는
 * 남기지 않는다. 응답 원문과 예외 메시지도 남기지 않는다.
 */
@Component
public class HttpHermesGatewayClient implements HermesGatewayClient {
    private final RestClient client;
    private final String baseUrl;
    private final String token;


    public HttpHermesGatewayClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = properties.dashboardBaseUrl().replaceAll("/$", "");
        this.token = properties.dashboardToken();
    }


    @Override
    public Optional<Instant> startedAt() {
        final JsonNode body;
        try {
            body = client.get()
                .uri(baseUrl + "/api/gateway")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JsonNode.class);
        } catch (RuntimeException ex) {
            // RestClientException 과 본문 변환 실패를 함께 받는다. 기동 시각을 모르는 것이다.
            return Optional.empty();
        }
        JsonNode value = body == null ? null : body.get("started_at");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(value.longValue()));
    }
}
