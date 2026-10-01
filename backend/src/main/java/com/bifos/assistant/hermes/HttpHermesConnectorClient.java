package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** connector 요청은 비밀값을 포함하므로 원격 오류 본문이나 cause 를 로그와 예외에 남기지 않는다. */
@Component
public class HttpHermesConnectorClient implements HermesConnectorClient {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String AUTHORIZATION = "Authorization";
    private static final String PROFILE = "profile";
    private static final String PLUGIN = "plugin";
    private static final String ENABLED = "enabled";
    private static final String RESTART_REQUIRED = "restart_required";
    private final RestClient client;
    private final String baseUrl;
    private final String token;

    public HttpHermesConnectorClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = properties.dashboardBaseUrl().replaceAll("/$", "");
        this.token = properties.dashboardToken();
    }

    @Override
    public List<ConnectorManifest> readCatalog() {
        JsonNode body = request(() -> client.get()
                .uri(baseUrl + "/api/connectors/catalog")
                .header(AUTHORIZATION, bearer())
                .retrieve()
                .body(JsonNode.class));
        if (!body.isArray()) {
            throw new IllegalStateException();
        }
        List<ConnectorManifest> manifests = new ArrayList<>();
        for (JsonNode item : body) {
            manifests.add(manifest(item));
        }
        return List.copyOf(manifests);
    }

    @Override
    public CallResult call(String connectorId, String tool, Map<String, String> values) {
        JsonNode body = request(() -> client.post()
                .uri(baseUrl + "/api/connectors/{id}/call", connectorId)
                .header(AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tool", tool, "values", values))
                .retrieve()
                .body(JsonNode.class));
        if (requiredBoolean(body, "ok")) {
            JsonNode result = body.get("result");
            if (result == null || result.isNull()) {
                throw new IllegalStateException();
            }
            return CallResult.success(result);
        }
        return CallResult.failure(
                ConnectorCallError.fromWord(text(body, "error")).orElseThrow(IllegalStateException::new));
    }

    @Override
    public boolean putConnector(String profile, String connectorId, boolean enabled) {
        JsonNode body = request(() -> client.put()
                .uri(baseUrl + "/api/connectors")
                .header(AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(PROFILE, profile, PLUGIN, connectorId, ENABLED, enabled))
                .retrieve()
                .body(JsonNode.class));
        requireText(body, PROFILE, profile);
        requireText(body, PLUGIN, connectorId);
        if (requiredBoolean(body, ENABLED) != enabled) {
            throw new IllegalStateException();
        }
        return requiredBoolean(body, RESTART_REQUIRED);
    }

    @Override
    public ConnectorState readConnector(String profile, String connectorId) {
        JsonNode response = request(() -> client.get()
                .uri(baseUrl + "/api/connectors?profile={profile}", profile)
                .header(AUTHORIZATION, bearer())
                .retrieve()
                .body(JsonNode.class));
        JsonNode connectors = response.get("connectors");
        requireText(response, PROFILE, profile);
        if (connectors == null || !connectors.isArray()) {
            throw new IllegalStateException();
        }
        for (JsonNode item : connectors) {
            if (connectorId.equals(text(item, PLUGIN))) {
                return new ConnectorState(
                        profile, requiredBoolean(item, ENABLED), requiredBoolean(item, "configured"), false);
            }
        }
        // 대시보드는 운영 목록에도 없고 소유 기록도 없는 plugin 을 목록에 넣지 않는다. 설치되지 않은 것이다.
        // 응답 모양이 틀린 것은 위에서 예외로 끝났으므로 여기 오는 것은 모양이 맞는 응답뿐이다.
        return new ConnectorState(profile, false, false, false);
    }

    @Override
    public ProbeResult probe(String profile, String mcpServer) {
        JsonNode response = request(() -> client.post()
                .uri(baseUrl + "/api/mcp/servers/{server}/test?profile={profile}", mcpServer, profile)
                .header(AUTHORIZATION, bearer())
                .retrieve()
                .body(JsonNode.class));
        JsonNode tools = response.get("tools");
        if (tools == null || !tools.isArray()) {
            throw new IllegalStateException();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(requiredText(tool, "name"));
        }
        return new ProbeResult(requiredBoolean(response, "ok"), List.copyOf(names));
    }

    @Override
    public boolean putEnv(String profile, String key, String value) {
        JsonNode body = request(() -> client.put()
                .uri(baseUrl + "/api/env")
                .header(AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(PROFILE, profile, "key", key, "value", value))
                .retrieve()
                .body(JsonNode.class));
        requireText(body, PROFILE, profile);
        requireText(body, "key", key);
        return requiredBoolean(body, RESTART_REQUIRED);
    }

    @Override
    public boolean deleteEnv(String profile, String key) {
        try {
            ResponseEntity<String> response = client.method(HttpMethod.DELETE)
                    .uri(baseUrl + "/api/env")
                    .header(AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(PROFILE, profile, "key", key))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, ignored) -> {})
                    .toEntity(String.class);
            if (response.getStatusCode().value() == 404) {
                return false;
            }
            if (response.getBody() == null) {
                throw new IllegalStateException();
            }
            JsonNode body = MAPPER.readTree(response.getBody());
            requireText(body, PROFILE, profile);
            requireText(body, "key", key);
            return requiredBoolean(body, RESTART_REQUIRED);
        } catch (RuntimeException ex) {
            throw new IllegalStateException();
        }
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private static ConnectorManifest manifest(JsonNode item) {
        JsonNode declared = item.get("fields");
        if (declared == null || !declared.isArray()) {
            throw new IllegalStateException();
        }
        List<ConnectorField> fields = new ArrayList<>();
        for (JsonNode field : declared) {
            fields.add(field(field));
        }
        String description = text(item, "description");
        return new ConnectorManifest(
                requiredText(item, "id"),
                requiredText(item, "title"),
                description == null ? "" : description,
                fields,
                requiredText(item.get("verify"), "tool"),
                requiredText(item, "mcp_server"),
                toolsets(item.get("toolsets")),
                optionalBoolean(item, "attachments", false));
    }

    /** 옛 대시보드 plugin 은 이 칸을 내지 않는다. 없으면 빈 목록이다. */
    private static List<String> toolsets(JsonNode declared) {
        if (declared == null || declared.isNull()) {
            return List.of();
        }
        if (!declared.isArray()) {
            throw new IllegalStateException();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : declared) {
            if (!name.isString() || name.asString().isBlank()) {
                throw new IllegalStateException();
            }
            names.add(name.asString());
        }
        return List.copyOf(names);
    }

    /** manifest 에서 생략할 수 있는 칸의 기본값은 {@code docs/connectors.md} 의 「connector.json」 과 같다. */
    private static ConnectorField field(JsonNode field) {
        String key = requiredText(field, "key");
        String label = text(field, "label");
        String description = text(field, "description");
        return new ConnectorField(
                key,
                requiredText(field, "env"),
                label == null ? key : label,
                description == null ? "" : description,
                optionalBoolean(field, "secret", false),
                optionalBoolean(field, "required", true),
                text(field, "pattern"),
                options(field.get("options")));
    }

    private static ConnectorFieldOptions options(JsonNode options) {
        if (options == null || options.isNull()) {
            return null;
        }
        return new ConnectorFieldOptions(
                requiredText(options, "tool"),
                requiredText(options, "items"),
                requiredText(options, "value"),
                requiredText(options, "label"),
                optionalBoolean(options, "auto_select_single", false));
    }

    private static JsonNode request(Supplier<JsonNode> call) {
        try {
            JsonNode body = call.get();
            if (body == null) {
                throw new IllegalStateException();
            }
            return body;
        } catch (RestClientException | IllegalStateException ex) {
            throw new IllegalStateException();
        }
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException();
        }
        return value.asBoolean();
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalStateException();
        }
        return value.asBoolean();
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(text(node, field))) {
            throw new IllegalStateException();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
