package com.bifos.assistant.mcp.presentation;

import static com.bifos.assistant.mcp.presentation.McpRpc.INVALID_ARGUMENTS;
import static com.bifos.assistant.mcp.presentation.McpRpc.invalidParams;
import static com.bifos.assistant.mcp.presentation.McpRpc.response;
import static com.bifos.assistant.mcp.presentation.McpRpc.text;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteRequest;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.presentation.McpDtos.ArtifactWriteArguments;
import com.bifos.assistant.shared.error.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@code artifact_write} 의 인자 검사와 호출이다. {@link McpController} 가 이름으로 이곳에 보낸다. */
@Component
@RequiredArgsConstructor
public class McpArtifactCalls {
    static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final McpToolService tools;


    Map<String, Object> writeArtifact(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!onlyArtifactFields(arguments)
            || !text(arguments, "conversation_id")
            || !text(arguments, "path")
            || !exactlyOneText(arguments, "content", "source_url")
            || !optionalText(arguments, "title")
            || !optionalText(arguments, "description")) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        String conversationId = arguments.get("conversation_id").asString();
        if (!UUID_TEXT.matcher(conversationId).matches()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(conversationId);
        } catch (IllegalArgumentException ex) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        ArtifactWriteArguments value = ArtifactWriteArguments.from(arguments);
        try {
            return response(
                id,
                tools.writeArtifact(
                    caller,
                    new ArtifactWriteRequest(
                        parsed, value.path(), value.content(), value.sourceUrl(), value.title(), value.description())));
        } catch (ApiException ex) {
            return invalidParams(id, artifactValidationReason(ex));
        }
    }


    private static boolean onlyArtifactFields(JsonNode arguments) {
        Set<String> allowed = Set.of("conversation_id", "path", "content", "source_url", "title", "description");
        return allowed.containsAll(arguments.propertyNames());
    }


    /** 선택 인자다. 없거나 {@code null} 이거나 글이다. */
    private static boolean optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() || value.isTextual();
    }


    private static boolean exactlyOneText(JsonNode arguments, String first, String second) {
        boolean hasFirst = arguments.has(first);
        boolean hasSecond = arguments.has(second);
        return hasFirst != hasSecond
            && text(arguments, hasFirst ? first : second)
            && (!hasSecond || !arguments.get(second).asString().isBlank());
    }


    /** 내부 예외 문구를 그대로 내보내지 않고, 정해 둔 오류 이유만 반환한다. */
    private static String artifactValidationReason(ApiException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return INVALID_ARGUMENTS;
        }
        return switch (message) {
            case "inline artifact content must be HTML or CSS", "URL artifact path must be an image" ->
                "허용하지 않은 확장자입니다.";
            case "artifact path is invalid", "artifact path is required" -> "경로 형식이 올바르지 않습니다.";
            case "artifact content must not exceed 5 MiB" -> "파일 크기가 5MB를 초과했습니다.";
            case "artifact source URL is invalid" -> "주소 형식이 올바르지 않습니다.";
            default -> INVALID_ARGUMENTS;
        };
    }
}
