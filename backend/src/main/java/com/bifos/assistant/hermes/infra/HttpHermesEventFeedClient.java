package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.hermes.application.port.HermesEventFeedClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * 대시보드 plugin 의 {@code POST /api/connectors/<id>/events} 를 부른다(ADR-20261105 / event-watch).
 *
 * <p>응답의 항목은 식별자, 받은 시각, 라벨만 읽는다. 다른 칸이 와도 버린다. 원격 오류 본문과 응답 원문을 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class HttpHermesEventFeedClient implements HermesEventFeedClient {

    /** 대시보드의 조회 전체 제한(30초)보다 길어야 대시보드가 내는 실패 응답을 받는다. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(40);

    /** 받는 항목 수의 상한이다. 대시보드의 쪽 상한과 쪽 크기를 넉넉히 덮는다. */
    static final int MAX_ITEMS = 500;

    /** 대시보드가 돌려주는 식별자 모양과 같다. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private static final int MAX_LABELS = 50;
    private static final int MAX_LABEL_LENGTH = 64;

    private final RestClient client;
    private final String baseUrl;
    private final String token;


    public HttpHermesEventFeedClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = properties.dashboardBaseUrl().replaceAll("/$", "");
        this.token = properties.dashboardToken();
    }


    @Override
    public ConnectorEventListing listEvents(String connectorId, String profile, Instant since) {
        final JsonNode body;
        try {
            body = client.post()
                .uri(baseUrl + "/api/connectors/{id}/events", connectorId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("profile", profile, "since", Math.max(0, since.getEpochSecond())))
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("사건 감지 목록을 받지 못했다 connector={} error={}", connectorId, ex.getClass().getSimpleName());
            return ConnectorEventListing.failure(ConnectorCallError.UNAVAILABLE);
        }
        try {
            return read(body);
        } catch (IllegalStateException ex) {
            log.warn("사건 감지 목록의 모양이 틀렸다 connector={}", connectorId);
            return ConnectorEventListing.failure(ConnectorCallError.UNAVAILABLE);
        }
    }


    /** {@code {ok: true, items, truncated}} 나 {@code {ok: false, error}} 를 읽는다. 모양이 틀린 항목 하나는 버린다. */
    static ConnectorEventListing read(JsonNode body) {
        JsonNode ok = body == null ? null : body.get("ok");
        if (ok == null || !ok.isBoolean()) {
            throw new IllegalStateException();
        }
        if (!ok.booleanValue()) {
            JsonNode error = body.get("error");
            return ConnectorEventListing.failure(ConnectorCallError
                .fromWord(error != null && error.isString() ? error.asString() : "")
                .orElse(ConnectorCallError.UNAVAILABLE));
        }
        JsonNode items = body.get("items");
        if (items == null || !items.isArray() || items.size() > MAX_ITEMS) {
            throw new IllegalStateException();
        }
        List<ConnectorEventListing.Item> read = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            if (id == null || !id.isString() || !ID.matcher(id.asString()).matches()) {
                continue;
            }
            read.add(new ConnectorEventListing.Item(id.asString(), instant(item.get("received_at")), labels(item.get("labels"))));
        }
        JsonNode truncated = body.get("truncated");
        return ConnectorEventListing.success(read, truncated != null && truncated.isBoolean() && truncated.booleanValue());
    }


    private static Instant instant(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }


    private static List<String> labels(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode label : value) {
            if (label.isString() && label.asString().length() <= MAX_LABEL_LENGTH && labels.size() < MAX_LABELS) {
                labels.add(label.asString());
            }
        }
        return labels;
    }
}
