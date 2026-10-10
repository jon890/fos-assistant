package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorErrorDetail;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 금융 소비 경로의 strict 입력과 읽기 전용 준비 전송이다. 운영 승인 경로는 아직 호출하지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HttpHermesConnectorGuard {
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static ObjectNode approved(String profile, String tool, ConnectorApprovedExecution execution) {
        try {
            String raw = execution.argsJson();
            if (raw.getBytes(StandardCharsets.UTF_8).length > 16384
                    || !Sha256.hex(raw).equals(execution.argsSha256())
                    || execution.ticket() == null
                    || !execution.ticket().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")
                    || execution.ticket().length() > 4096) {
                return null;
            }
            ObjectNode body = body(profile, tool, raw);
            if (body == null) {
                return null;
            }
            JsonNode payload = MAPPER.readTree(
                    Base64.getUrlDecoder().decode(execution.ticket().split("\\.")[0]));
            if (!payload.path("argsSha256").isString()
                    || !execution.argsSha256().equals(payload.path("argsSha256").stringValue())) {
                return null;
            }
            ObjectNode guard = body.putObject("execution");
            guard.put("v", 1);
            guard.put("protocol", "approval-claim-v1");
            guard.put("ticket", execution.ticket());
            guard.put("argsJson", raw);
            guard.put("argsSha256", execution.argsSha256());
            return body;
        } catch (Exception ignored) {
            return null;
        }
    }

    static ObjectNode body(String profile, String tool, String raw) {
        try {
            JsonNode args = MAPPER.readTree(raw);
            if (!args.isObject() || raw.getBytes(StandardCharsets.UTF_8).length > 16384) {
                return null;
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("profile", profile);
            body.put("hermes_tool", tool);
            body.set("args", args);
            return body;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static RestClient prepareClient(RestClient client, HermesProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(2));
        return client.mutate().requestFactory(factory).build();
    }

    static JsonNode supportState(JsonNode node) {
        if (node != null
                && node.isObject()
                && node.path("protocol").isString()
                && "approval-claim-v1".equals(node.path("protocol").stringValue())
                && node.path("state").isString()
                && Set.of("unsupported", "pending", "verified")
                        .contains(node.path("state").stringValue())
                && Set.of("protocol", "state", "bindingId", "connectionId", "connectionUpdatedAt", "manifestSha256")
                        .containsAll(node.propertyNames())
                && node.properties().stream().allMatch(entry -> entry.getValue().isString())
                && ("unsupported".equals(node.path("state").stringValue())
                        || (node.size() == 6
                                && node.path("bindingId").stringValue().matches("[1-9][0-9]{0,18}")
                                && node.path("connectionId").stringValue().matches("[1-9][0-9]{0,18}")
                                && node.path("manifestSha256").stringValue().matches("[0-9a-f]{64}")))) {
            return node;
        }
        return MAPPER.createObjectNode().put("protocol", "approval-claim-v1").put("state", "unsupported");
    }

    static CallResult prepare(RestClient client, String base, String token, String connector, ObjectNode body) {
        if (body == null) {
            return CallResult.failure(ConnectorCallError.INVALID_INPUT);
        }
        try {
            var response = client.post()
                    .uri(base + "/api/connectors/{id}/prepare", connector)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, ignored) -> {})
                    .toEntity(String.class);
            String raw = response.getBody();
            if (response.getStatusCode().value() != 200
                    || raw == null
                    || raw.getBytes(StandardCharsets.UTF_8).length > 32768) {
                return CallResult.failure(ConnectorCallError.UNAVAILABLE);
            }
            JsonNode answer = MAPPER.readTree(raw);
            JsonNode result = answer.get("result");
            if (answer.path("ok").isBoolean()
                    && answer.path("ok").booleanValue()
                    && result != null
                    && result.isObject()
                    && result.propertyNames().equals(Set.of("v", "executionArgs", "summary"))
                    && result.path("v").isInt()
                    && result.path("v").intValue() == 1
                    && result.path("executionArgs").isObject()
                    && result.path("summary").isObject()) {
                return CallResult.success(result);
            }
        } catch (RuntimeException ignored) {
            // 읽기 전용 준비는 재시도 없이 미지원으로 끝낸다. 원문과 cause는 남기지 않는다.
        }
        return CallResult.failure(ConnectorCallError.UNAVAILABLE);
    }

    static CallResult execute(RestClient client, String base, String token, String connector, ObjectNode body) {
        if (body == null) {
            return CallResult.failure(ConnectorCallError.INVALID_INPUT);
        }
        try {
            var response = client.post()
                    .uri(base + "/api/connectors/{id}/execute", connector)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, ignored) -> {})
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            if (Set.of(400, 401, 404).contains(status) || (status == 409 && body.has("execution"))) {
                return CallResult.failure(ConnectorCallError.UNAVAILABLE);
            }
            if (status != 200 || response.getBody() == null) {
                throw new ConnectorExecutionUnknown();
            }
            JsonNode answer = MAPPER.readTree(response.getBody());
            if (!answer.path("ok").isBoolean()) {
                throw new ConnectorExecutionUnknown();
            }
            if (answer.path("ok").booleanValue()) {
                JsonNode result = answer.get("result");
                if (result == null || result.isNull()) {
                    throw new ConnectorExecutionUnknown();
                }
                return CallResult.success(result);
            }
            return CallResult.failure(
                    ConnectorCallError.fromWord(answer.path("error").asString())
                            .orElseThrow(ConnectorExecutionUnknown::new),
                    ConnectorErrorDetail.fromAnswer(answer).orElse(null));
        } catch (RuntimeException ignored) {
            throw new ConnectorExecutionUnknown();
        }
    }
}
