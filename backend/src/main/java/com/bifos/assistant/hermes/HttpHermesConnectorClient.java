package com.bifos.assistant.hermes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** connector 요청은 비밀값을 포함하므로 원격 오류 본문이나 cause 를 로그에 남기지 않는다. */
@Component
public class HttpHermesConnectorClient implements HermesConnectorClient {
    private static final String PLUGIN = "fos-accountbook";
    private final RestClient client;
    private final String baseUrl;
    private final String token;
    public HttpHermesConnectorClient(HermesProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = properties.dashboardBaseUrl().replaceAll("/$", ""); this.token = properties.dashboardToken();
    }
    @Override public boolean putConnector(String profile, boolean enabled) {
        JsonNode body = request(() -> client.put().uri(baseUrl + "/api/connectors").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("profile", profile, "plugin", PLUGIN, "enabled", enabled)).retrieve().body(JsonNode.class));
        requireText(body, "profile", profile); requireText(body, "plugin", PLUGIN); if (requiredBoolean(body, "enabled") != enabled) throw new IllegalStateException();
        return requiredBoolean(body, "restart_required");
    }
    @Override public ConnectorState readConnector(String profile) {
        JsonNode response = request(() -> client.get().uri(baseUrl + "/api/connectors?profile={profile}", profile)
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
        JsonNode connectors = response == null ? null : response.get("connectors");
        requireText(response, "profile", profile);
        if (connectors == null || !connectors.isArray()) throw new IllegalStateException();
        for (JsonNode item : connectors) {
            if (PLUGIN.equals(text(item, "plugin"))) return new ConnectorState(profile, requiredBoolean(item, "enabled"), requiredBoolean(item, "configured"), false);
        }
        throw new IllegalStateException();
    }
    @Override public ProbeResult probeAccountbook(String profile) {
        JsonNode response = request(() -> client.post().uri(baseUrl + "/api/mcp/servers/accountbook/test?profile={profile}", profile)
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
        JsonNode tools = response == null ? null : response.get("tools");
        if (tools == null || !tools.isArray()) throw new IllegalStateException();
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) { String name = text(tool, "name"); if (name == null) throw new IllegalStateException(); names.add(name); }
        return new ProbeResult(requiredBoolean(response, "ok"), List.copyOf(names));
    }
    @Override public boolean putEnv(String profile, String key, String value) {
        JsonNode body = request(() -> client.put().uri(baseUrl + "/api/env").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("profile", profile, "key", key, "value", value)).retrieve().body(JsonNode.class));
        requireText(body, "profile", profile); requireText(body, "key", key);
        return requiredBoolean(body, "restart_required");
    }
    @Override public boolean deleteEnv(String profile, String key) {
        try {
            ResponseEntity<String> response = client.method(HttpMethod.DELETE).uri(baseUrl + "/api/env").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("profile", profile, "key", key))
                    .retrieve().onStatus(status -> status.value() == 404, (request, ignored) -> {}).toEntity(String.class);
            if (response.getStatusCode().value() == 404) return false;
            if (response.getBody() == null) throw new IllegalStateException();
            JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getBody());
            requireText(body, "profile", profile); requireText(body, "key", key);
            return requiredBoolean(body, "restart_required");
        } catch (RuntimeException ex) { throw new IllegalStateException(); }
    }
    private interface Request { JsonNode run(); }
    private static JsonNode request(Request call) { try { JsonNode body = call.run(); if (body == null) throw new IllegalStateException(); return body; } catch (RestClientException | IllegalStateException ex) { throw new IllegalStateException(); } }
    private static boolean requiredBoolean(JsonNode node, String field) { JsonNode value = node == null ? null : node.get(field); if (value == null || !value.isBoolean()) throw new IllegalStateException(); return value.asBoolean(); }
    private static void requireText(JsonNode node, String field, String expected) { if (!expected.equals(text(node, field))) throw new IllegalStateException(); }
    private static String text(JsonNode node, String field) { JsonNode value = node == null ? null : node.get(field); return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
}
