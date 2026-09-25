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
        open(apiBaseUrl, profileName, runId, onEvent, stream -> {});
    }

    public void open(
            String apiBaseUrl, String profileName, String runId, Consumer<RunEvent> onEvent,
            Consumer<java.io.Closeable> onOpened) {
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
            onOpened.accept(body);
            readEvents(body, onEvent);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not read the Hermes event stream");
        } catch (IOException ex) {
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
            onEvent.accept(toRunEvent(objectMapper.readTree(raw)));
        } catch (JacksonException ex) {
            throw new IOException("Hermes sent an invalid event", ex);
        }
    }

    /**
     * 사건 하나의 JSON 을 {@link RunEvent} 로 옮긴다.
     *
     * <p>Hermes 는 사건 이름을 {@code event} 로 보낸다. 실측으로 확인했다. {@code type} 을 함께 보는
     * 것은 다른 형태로 보내는 구현이 섞일 때를 위한 것이다.
     */
    static RunEvent toRunEvent(JsonNode root) {
        JsonNode payload = root.path("data");
        return new RunEvent(
                firstText(root, payload, "event", "type"),
                firstText(root, payload, "delta", "text", "output"),
                firstText(root, payload, "tool", "tool_name", "toolName", "name"),
                firstText(root, payload, "preview", "detail", "result"),
                durationMs(root, payload),
                failed(root, payload),
                firstText(root, payload, "subagent_id"),
                firstText(root, payload, "goal"),
                firstText(root, payload, "model"),
                firstText(root, payload, "child_session_id"),
                firstNumber(root, payload, "input_tokens"),
                firstNumber(root, payload, "output_tokens"),
                firstText(root, payload, "status"));
    }

    /** Hermes 는 걸린 시간을 초 단위 실수로 보낸다. 1000 을 곱해 밀리초 정수로 옮긴다. */
    private static Long durationMs(JsonNode root, JsonNode payload) {
        JsonNode value = number(root, "duration");
        if (value == null) {
            value = number(payload, "duration");
        }
        if (value == null) {
            value = number(root, "duration_seconds");
        }
        if (value == null) {
            value = number(payload, "duration_seconds");
        }
        return value == null ? null : Math.round(value.asDouble() * 1000);
    }

    /** {@code error} 를 보내지 않으면 실패인지 아닌지 모른다는 뜻으로 null 을 낸다. */
    private static Boolean failed(JsonNode root, JsonNode payload) {
        JsonNode value = bool(root, "error");
        if (value == null) {
            value = bool(payload, "error");
        }
        if (value != null) {
            return value.asBoolean();
        }
        String status = firstText(root, payload, "status");
        return status == null ? null : !"completed".equalsIgnoreCase(status);
    }

    private static Long firstNumber(JsonNode root, JsonNode payload, String field) {
        JsonNode value = number(root, field);
        if (value == null) {
            value = number(payload, field);
        }
        return value == null ? null : value.asLong();
    }

    private static JsonNode number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value : null;
    }

    private static JsonNode bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value : null;
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
