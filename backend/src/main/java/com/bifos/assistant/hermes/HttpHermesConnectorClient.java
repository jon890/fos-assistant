package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** connector 요청은 비밀값을 포함하므로 원격 오류 본문이나 cause 를 로그와 예외에 남기지 않는다. */
@Component
public class HttpHermesConnectorClient implements HermesConnectorClient {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String AUTHORIZATION = "Authorization";
    private static final String PROFILE = "profile";
    private static final String PLUGIN = "plugin";
    private static final String ENABLED = "enabled";
    private static final String RESTART_REQUIRED = "restart_required";
    private static final int SCHEMA_WITHOUT_TOOLS = 1;
    /** 읽을 수 없는 선언에 주는 판이다. 받는 쪽이 아는 판이 아니라 그 커넥터만 카탈로그에서 빠진다. */
    private static final int SCHEMA_UNREADABLE = 0;
    /** 실행 경로의 읽기 제한이다. 대시보드의 실행 제한 60초보다 길어야 대시보드가 내는 시간 초과 응답을 받는다. */
    private static final Duration EXECUTE_READ_TIMEOUT = Duration.ofSeconds(75);
    /** 실행 경로가 호출을 실행하지 않고 거절했다는 뜻의 상태다. */
    private static final Set<Integer> EXECUTE_NOT_RUN = Set.of(400, 401, 404);

    private final RestClient client;
    private final RestClient executeClient;
    private final String baseUrl;
    private final String token;

    public HttpHermesConnectorClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        SimpleClientHttpRequestFactory executeFactory = new SimpleClientHttpRequestFactory();
        executeFactory.setConnectTimeout(properties.connectTimeout());
        executeFactory.setReadTimeout(EXECUTE_READ_TIMEOUT);
        // 읽기 제한만 다르다. 나머지 구성은 위의 클라이언트와 함께 쓴다.
        this.executeClient = client.mutate().requestFactory(executeFactory).build();
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

    /**
     * 성공 응답만 결과로 읽는다. 시간 초과, 끊긴 연결, 서버 오류, 읽을 수 없는 본문은 실행됐는지 알 수 없는 것이다.
     *
     * <p>인자는 글자가 아니라 JSON 값으로 보낸다. 바이트는 승인한 글과 달라질 수 있고 값은 같다.
     */
    @Override
    public CallResult execute(String profile, String connectorId, String hermesTool, String argsJson) {
        JsonNode args = arguments(argsJson);
        if (args == null) {
            // 보내지 않았으므로 실행되지 않은 것이 분명하다.
            return CallResult.failure(ConnectorCallError.INVALID_INPUT);
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put(PROFILE, profile);
        body.put("hermes_tool", hermesTool);
        body.set("args", args);
        final ResponseEntity<String> response;
        try {
            response = executeClient
                    .post()
                    .uri(baseUrl + "/api/connectors/{id}/execute", connectorId)
                    .header(AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, ignored) -> {})
                    .toEntity(String.class);
        } catch (RestClientException ex) {
            throw new ConnectorExecutionUnknown();
        }
        int status = response.getStatusCode().value();
        if (EXECUTE_NOT_RUN.contains(status)) {
            return CallResult.failure(ConnectorCallError.UNAVAILABLE);
        }
        if (status != 200 || response.getBody() == null) {
            throw new ConnectorExecutionUnknown();
        }
        try {
            JsonNode answer = MAPPER.readTree(response.getBody());
            if (requiredBoolean(answer, "ok")) {
                JsonNode result = answer.get("result");
                if (result == null || result.isNull()) {
                    throw new ConnectorExecutionUnknown();
                }
                return CallResult.success(result);
            }
            return CallResult.failure(
                    ConnectorCallError.fromWord(text(answer, "error")).orElseThrow(ConnectorExecutionUnknown::new));
        } catch (JacksonException | IllegalStateException ex) {
            throw new ConnectorExecutionUnknown();
        }
    }

    /** 승인 줄에 저장한 인자 글을 JSON object 로 읽는다. object 로 읽을 수 없으면 null 이다. */
    private static JsonNode arguments(String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            return args != null && args.isObject() ? args : null;
        } catch (JacksonException ex) {
            return null;
        }
    }

    @Override
    public InstallResult putConnector(String profile, String connectorId, boolean enabled) {
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
        return new InstallResult(
                requiredBoolean(body, RESTART_REQUIRED), optionalBoolean(body, "plugin_updated", false));
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
        // 옛 대시보드 plugin 은 이 칸을 내지 않는다. JSON true 일 때만 참이다.
        JsonNode hook = response.get("policy_hook");
        boolean policyHook = hook != null && hook.isBoolean() && hook.asBoolean();
        for (JsonNode item : connectors) {
            if (connectorId.equals(text(item, PLUGIN))) {
                return new ConnectorState(
                        profile,
                        requiredBoolean(item, ENABLED),
                        requiredBoolean(item, "configured"),
                        false,
                        policyHook);
            }
        }
        // 대시보드는 운영 목록에도 없고 소유 기록도 없는 plugin 을 목록에 넣지 않는다. 설치되지 않은 것이다.
        // 응답 모양이 틀린 것은 위에서 예외로 끝났으므로 여기 오는 것은 모양이 맞는 응답뿐이다.
        return new ConnectorState(profile, false, false, false, policyHook);
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
        JsonNode schema = item.get("schema");
        JsonNode tools = item.get("tools");
        // 판이나 도구 선언의 모양이 틀리면 둘을 함께 버린다. 도구만 비우면 도구를 선언하지 않는 판으로 읽혀 통과한다.
        boolean readable = readableSchema(schema) && readableTools(tools);
        return new ConnectorManifest(
                requiredText(item, "id"),
                requiredText(item, "title"),
                description == null ? "" : description,
                fields,
                requiredText(item.get("verify"), "tool"),
                requiredText(item, "mcp_server"),
                toolsets(item.get("toolsets")),
                optionalBoolean(item, "attachments", false),
                readable ? schema(schema) : SCHEMA_UNREADABLE,
                readable ? tools(tools) : List.of());
    }

    private static boolean readableSchema(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isInt();
    }

    private static boolean readableTools(JsonNode declared) {
        return declared == null || declared.isNull() || declared.isObject();
    }

    /** 옛 대시보드 plugin 은 이 칸을 내지 않는다. 없으면 도구를 선언하지 않는 판이다. */
    private static int schema(JsonNode declared) {
        return declared == null || declared.isNull() ? SCHEMA_WITHOUT_TOOLS : declared.asInt();
    }

    /**
     * 도구 이름을 키로 하는 객체를 읽는다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없으면 빈 목록이다.
     *
     * <p>위험도와 승인 방식은 글자 그대로 담고 없으면 null 로 둔다. 뜻을 읽고 거르는 것은 부르는 쪽이 한다. 여기서
     * 거절하면 선언이 틀린 커넥터 하나 때문에 카탈로그 전체를 읽지 못한다. 객체가 아닌 값은 부르는 쪽이 먼저 거른다.
     */
    private static List<ConnectorTool> tools(JsonNode declared) {
        if (declared == null || declared.isNull()) {
            return List.of();
        }
        List<ConnectorTool> tools = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : declared.properties()) {
            JsonNode policy = entry.getValue();
            tools.add(new ConnectorTool(
                    entry.getKey(),
                    text(policy, "risk"),
                    text(policy, "approval"),
                    text(policy, "title"),
                    grant(policy)));
        }
        return List.copyOf(tools);
    }

    /**
     * 상시 허락을 줄 수 있는지의 선언이다(ADR-065). 칸이 없으면 null 이고 받는 쪽이 승인 방식으로 정한다.
     *
     * <p>boolean 이 아닌 값은 거절하지 않고 거짓으로 읽는다. 형식은 대시보드 plugin 이 검사하고, 여기서는 읽을 수 없는
     * 선언이 상시 허락을 여는 쪽으로 읽히지 않게만 한다.
     */
    private static Boolean grant(JsonNode policy) {
        JsonNode value = policy == null ? null : policy.get("grant");
        if (value == null) {
            return null;
        }
        return value.isBoolean() && value.asBoolean();
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
