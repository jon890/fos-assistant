package com.bifos.assistant.mcp.presentation;

import static com.bifos.assistant.mcp.presentation.McpRpc.INVALID_ARGUMENTS;
import static com.bifos.assistant.mcp.presentation.McpRpc.invalidParams;
import static com.bifos.assistant.mcp.presentation.McpRpc.response;
import static com.bifos.assistant.mcp.presentation.McpRpc.text;

import com.bifos.assistant.mcp.application.McpFileSpaceTools;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code file_space_put} 의 인자 검사와 호출이다. {@link McpController} 가 이름으로 이곳에 보낸다.
 *
 * <p>인자는 {@code path}, {@code title}, {@code description} 셋만 받는다. 대화와 사용자는 인자로 받지 않는다(ADR-032).
 * 모델은 쓰지 않는 선택 인자에 {@code null} 을 자주 보내므로 선택 인자의 {@code null} 은 없는 것으로 본다.
 */
@Component
@RequiredArgsConstructor
public class McpFileSpaceCalls {
    private static final Set<String> FIELDS = Set.of("path", "title", "description");

    private final McpFileSpaceTools fileSpace;


    Map<String, Object> put(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!FIELDS.containsAll(arguments.propertyNames())
            || !text(arguments, "path")
            || arguments.get("path").asString().isBlank()
            || !optionalText(arguments, "title")
            || !optionalText(arguments, "description")) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(
            id,
            fileSpace.put(
                caller, arguments.get("path").asString(), optional(arguments, "title"), optional(arguments, "description")));
    }


    private static boolean optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() || value.isTextual();
    }


    private static String optional(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }
}
