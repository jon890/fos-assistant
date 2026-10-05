package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    /** 모델이 스킬을 읽는 Hermes 도구의 이름이다. */
    private static final String SKILL_VIEW_TOOL = "skill_view";

    private static final String SKILL_VIEW_STARTED = "tool.started";

    /**
     * Memory 본문을 읽는 Control Plane MCP 도구 이름의 끝이다. Hermes 는 {@code mcp__fos_assistant__memory_read} 처럼
     * 서버 이름을 앞에 붙여 보낸다.
     */
    private static final String MEMORY_READ_TOOL_SUFFIX = "memory_read";

    /** 인자를 {@code preview} 에 싣는 도구 시작 사건이다. */
    private static final String MEMORY_READ_STARTED = "tool.started";

    /** 할 일을 제안하는 Control Plane MCP 도구 이름의 끝이다. 인자에 할 일 제목이 실린다. */
    private static final String FOLLOW_UP_PROPOSE_TOOL_SUFFIX = "follow_up_propose";

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

    public void open(String apiBaseUrl, String profileName, String runId, Consumer<RunEvent> onEvent) {
        open(apiBaseUrl, profileName, runId, onEvent, stream -> {});
    }

    public void open(
            String apiBaseUrl,
            String profileName,
            String runId,
            Consumer<RunEvent> onEvent,
            Consumer<Closeable> onOpened) {
        open(apiBaseUrl, profileName, runId, onEvent, onOpened, ToolDetailScope.NONE);
    }

    /** @param scope 내용을 통째로 가릴 도구의 범위. 비밀값과 식별자는 범위와 관계없이 가린다 */
    public void open(
            String apiBaseUrl,
            String profileName,
            String runId,
            Consumer<RunEvent> onEvent,
            Consumer<Closeable> onOpened,
            ToolDetailScope scope) {
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
            readEvents(body, onEvent, scope);
        } catch (RestClientException ex) {
            throw HermesCallFailure.of(ex, "could not read the Hermes event stream");
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not read the Hermes event stream", ex);
        }
    }

    private void readEvents(InputStream body, Consumer<RunEvent> onEvent, ToolDetailScope scope) throws IOException {
        Map<String, String> identifiers = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emit(data, onEvent, scope, identifiers);
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
            emit(data, onEvent, scope, identifiers);
        }
    }

    private void emit(
            StringBuilder data, Consumer<RunEvent> onEvent, ToolDetailScope scope, Map<String, String> identifiers)
            throws IOException {
        if (data.isEmpty()) {
            return;
        }
        String raw = data.toString();
        data.setLength(0);
        try {
            onEvent.accept(toRunEvent(objectMapper.readTree(raw), scope, identifiers));
        } catch (JacksonException ex) {
            throw new IOException("Hermes sent an invalid event");
        }
    }

    /**
     * 사건 하나의 JSON 을 {@link RunEvent} 로 옮긴다.
     *
     * <p>Hermes 는 사건 이름을 {@code event} 로 보낸다. 실측으로 확인했다. {@code type} 을 함께 보는
     * 것은 다른 형태로 보내는 구현이 섞일 때를 위한 것이다.
     */
    static RunEvent toRunEvent(JsonNode root) {
        return toRunEvent(root, ToolDetailScope.NONE);
    }

    static RunEvent toRunEvent(JsonNode root, ToolDetailScope scope) {
        return toRunEvent(root, scope, new LinkedHashMap<>());
    }

    private static RunEvent toRunEvent(JsonNode root, ToolDetailScope scope, Map<String, String> identifiers) {
        JsonNode payload = root.path("data");
        String type = firstText(root, payload, "event", "type");
        String toolName = firstText(root, payload, "tool", "tool_name", "toolName", "name");
        String detail = firstDetail(root, payload);
        if (memoryReadResult(type, toolName)) {
            // 결과에 Memory 본문이 실린다. 인자를 담은 tool.started 의 preview 만 실행 사건에 남긴다(ADR-071)
            detail = null;
        }
        if (toolName != null && toolName.endsWith(FOLLOW_UP_PROPOSE_TOOL_SUFFIX)) {
            // 시작의 preview 에 할 일 제목이 실린다. 관리자가 실행 기록에서 남의 할 일 제목을 읽지 못하게
            // 시작과 끝 모두 남기지 않는다
            detail = null;
        }
        String skillName = null;
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("tool.")) {
            // 긴 스킬 이름은 token 으로 보여 가려진다. 스킬 사용 기록에 넘길 이름은 가리기 전에 꺼내 검증한다.
            if (!scope.hideAll() && SKILL_VIEW_STARTED.equalsIgnoreCase(type) && SKILL_VIEW_TOOL.equals(toolName)) {
                skillName = HermesSkillName.fromPreview(detail);
            }
            detail = ToolDetailRedactor.redact(detail, toolName, scope, identifiers);
        }
        return new RunEvent(
                type,
                firstText(root, payload, "delta", "text", "output"),
                toolName,
                detail,
                durationMs(root, payload),
                failed(root, payload),
                firstText(root, payload, "subagent_id"),
                firstText(root, payload, "goal"),
                firstText(root, payload, "model"),
                firstText(root, payload, "child_session_id"),
                firstNumber(root, payload, "input_tokens"),
                firstNumber(root, payload, "output_tokens"),
                firstText(root, payload, "status"),
                skillName);
    }

    /** {@code memory_read} 도구의 사건 가운데 시작이 아닌 것이다. Hermes 가 뒤에 결과를 싣기 시작해도 본문을 남기지 않는다. */
    private static boolean memoryReadResult(String type, String toolName) {
        return toolName != null
                && toolName.endsWith(MEMORY_READ_TOOL_SUFFIX)
                && !MEMORY_READ_STARTED.equalsIgnoreCase(type);
    }

    private static String firstDetail(JsonNode root, JsonNode payload) {
        for (String name : new String[] {"preview", "detail", "result"}) {
            JsonNode value = root.get(name);
            if (value == null || value.isNull()) {
                value = payload.get(name);
            }
            if (value != null && !value.isNull()) {
                return value.isValueNode() ? value.asText() : value.toString();
            }
        }
        return null;
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
