package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class HermesRunEventStream {

    private final RestClient restClient;
    private final HermesProfileKeyStore keyStore;
    private final ObjectMapper objectMapper;

    public HermesRunEventStream(
            HermesProfileKeyStore keyStore, HermesProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.runTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.keyStore = keyStore;
        this.objectMapper = objectMapper;
    }

    public void open(
            String apiBaseUrl, String profileName, String runId, Consumer<RunEvent> onEvent) {
        String apiKey = keyStore.resolve(profileName);
        try (InputStream body = restClient
                .get()
                .uri(apiBaseUrl + "/v1/runs/{runId}/events", runId)
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .body(InputStream.class)) {
            if (body == null) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "Hermes returned an empty event stream");
            }
            readEvents(body, onEvent);
        } catch (IOException | RestClientException ex) {
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not read the Hermes event stream", ex);
        }
    }

    private void readEvents(InputStream body, Consumer<RunEvent> onEvent) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emit(data, onEvent);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            emit(data, onEvent);
        }
    }

    private void emit(StringBuilder data, Consumer<RunEvent> onEvent) throws IOException {
        if (data.isEmpty()) {
            return;
        }
        String raw = data.toString();
        data.setLength(0);
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode payload = root.path("data");
            // Hermes 는 사건 이름을 `event` 로 보낸다. 실측으로 확인했다.
            // `type` 을 함께 보는 것은 다른 형태로 보내는 구현이 섞일 때를 위한 것이다.
            onEvent.accept(new RunEvent(
                    firstText(root, payload, "event", "type"),
                    firstText(root, payload, "delta", "text", "output"),
                    firstText(root, payload, "tool", "tool_name", "toolName", "name"),
                    firstText(root, payload, "preview", "detail", "status", "result")));
        } catch (JacksonException ex) {
            throw new IOException("Hermes sent an invalid event", ex);
        }
    }

    private static String firstText(JsonNode root, JsonNode payload, String... names) {
        for (String name : names) {
            String value = text(root, name);
            if (value == null) {
                value = text(payload, name);
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isValueNode() ? value.asText() : null;
    }
}
