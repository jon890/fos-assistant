package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorToolListing;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 대시보드 plugin 의 {@code POST /api/connectors/<id>/tools} 를 부르고 응답을 읽는다(ADR-20261101 / plugin-without-assistant-json).
 *
 * <p>응답 모양이 틀리거나 호출이 실패하면 원문 없는 {@link IllegalStateException} 이다. 값이 실린 요청이라 원격 오류 본문을 남기지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HttpHermesConnectorToolList {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 받는 도구 수의 상한이다. 넘는 서버는 확인하지 못한 것으로 둔다. */
    static final int MAX_TOOLS = 256;

    /** 도구 이름 길이의 상한이다. 승인 줄의 {@code tool_name} 컬럼과 같다. */
    static final int MAX_NAME_LENGTH = 128;

    /**
     * 받은 목록을 저장할 때의 JSON UTF-8 바이트 상한이다. {@code listed_tools} 의 {@code TEXT}(65,535바이트)보다 작게 둔다. 이름 길이는
     * 글자 수로 재므로 비ASCII 이름이 많으면 도구 수와 길이 상한 안에서도 넘칠 수 있어 따로 본다.
     */
    static final int MAX_STORED_BYTES = 60_000;


    static ConnectorToolListing post(
        RestClient client, String baseUrl, String token, String connectorId, Map<String, Object> request) {
        final JsonNode body;
        try {
            body = client.post()
                .uri(baseUrl + "/api/connectors/{id}/tools", connectorId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new IllegalStateException();
        }
        return read(body);
    }


    /** {@code {ok: true, tools: [{name, read_only}]}} 나 {@code {ok: false, error}} 를 읽는다. 이름이 겹치면 틀린 응답이다. */
    static ConnectorToolListing read(JsonNode body) {
        JsonNode ok = body == null ? null : body.get("ok");
        if (ok == null || !ok.isBoolean()) {
            throw new IllegalStateException();
        }
        if (!ok.booleanValue()) {
            JsonNode error = body.get("error");
            return ConnectorToolListing.failure(ConnectorCallError
                .fromWord(error != null && error.isString() ? error.asString() : "")
                .orElseThrow(IllegalStateException::new));
        }
        JsonNode tools = body.get("tools");
        if (tools == null || !tools.isArray() || tools.size() > MAX_TOOLS) {
            throw new IllegalStateException();
        }
        Map<String, Boolean> listed = new LinkedHashMap<>();
        for (JsonNode tool : tools) {
            JsonNode name = tool.get("name");
            JsonNode readOnly = tool.get("read_only");
            if (name == null || !name.isString() || name.asString().isEmpty()
                || name.asString().length() > MAX_NAME_LENGTH || readOnly == null || !readOnly.isBoolean()
                || listed.put(name.asString(), readOnly.booleanValue()) != null) {
                throw new IllegalStateException();
            }
        }
        if (storedBytes(listed) > MAX_STORED_BYTES) {
            throw new IllegalStateException();
        }
        return ConnectorToolListing.success(listed);
    }


    /** {@code ListedToolsConverter} 가 저장할 JSON 객체와 같은 모양의 UTF-8 바이트 수다. */
    private static int storedBytes(Map<String, Boolean> listed) {
        ObjectNode root = MAPPER.createObjectNode();
        listed.forEach(root::put);
        return MAPPER.writeValueAsString(root).getBytes(StandardCharsets.UTF_8).length;
    }
}
