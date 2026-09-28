package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.presentation.McpDtos.ArtifactWriteArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MemoryReadArguments;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class McpController {
    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private final McpToolService tools;
    private final CurrentUserProvider currentUser;
    private final BuildProperties buildProperties;
    @PostMapping("/mcp")
    public ResponseEntity<?> handle(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestBody JsonNode request) {
        if (origin != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        JsonNode id = request.get("id"); String method = request.path("method").asString();
        if ("notifications/initialized".equals(method)) return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> response(id, Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of("listChanged", false)), "serverInfo", Map.of("name", "fos-assistant-memory", "version", buildProperties.getVersion())));
            case "tools/list" -> response(id, Map.of("tools", tools.tools()));
            case "tools/call" -> call(id, request.path("params"));
            default -> error(id, -32601, "Method not found");
        });
    }
    private Map<String, Object> call(JsonNode id, JsonNode params) {
        JsonNode name = params.get("name");
        JsonNode arguments = params.get("arguments");
        if (name == null || !name.isTextual() || arguments == null || !arguments.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        return switch (name.asString()) {
            case "memory_read" -> readMemory(id, arguments);
            case "artifact_write" -> writeArtifact(id, arguments);
            default -> error(id, -32601, "Method not found");
        };
    }

    private Map<String, Object> readMemory(JsonNode id, JsonNode arguments) {
        JsonNode memoryId = arguments.get("id");
        if (memoryId == null || !memoryId.isIntegralNumber() || !memoryId.canConvertToLong()) {
            return error(id, -32602, "Invalid params");
        }
        return response(id, tools.readMemory(currentUser.require(), new MemoryReadArguments(memoryId.longValue()).id()));
    }

    private Map<String, Object> writeArtifact(JsonNode id, JsonNode arguments) {
        if (!onlyArtifactFields(arguments) || !text(arguments, "conversation_id") || !text(arguments, "path")
                || !exactlyOneText(arguments, "content", "source_url")) {
            return error(id, -32602, "Invalid params");
        }
        String conversationId = arguments.get("conversation_id").asString();
        if (!UUID_TEXT.matcher(conversationId).matches()) return error(id, -32602, "Invalid params");
        UUID parsed;
        try {
            parsed = UUID.fromString(conversationId);
        } catch (IllegalArgumentException ex) {
            return error(id, -32602, "Invalid params");
        }
        ArtifactWriteArguments value = ArtifactWriteArguments.from(arguments);
        try {
            return response(id, tools.writeArtifact(currentUser.require(), new ArtifactWriteRequest(parsed, value.path(), value.content(), value.sourceUrl())));
        } catch (ApiException ex) {
            return error(id, -32602, "Invalid params");
        }
    }

    private static boolean onlyArtifactFields(JsonNode arguments) {
        Set<String> allowed = Set.of("conversation_id", "path", "content", "source_url");
        return allowed.containsAll(arguments.propertyNames());
    }

    private static boolean text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value != null && value.isTextual();
    }

    private static boolean exactlyOneText(JsonNode arguments, String first, String second) {
        boolean hasFirst = arguments.has(first);
        boolean hasSecond = arguments.has(second);
        return hasFirst != hasSecond && text(arguments, hasFirst ? first : second);
    }

    private static Map<String, Object> response(JsonNode id, Map<String, Object> result) { Map<String, Object> body = base(id); body.put("result", result); return body; }
    private static Map<String, Object> error(JsonNode id, int code, String message) { Map<String, Object> body = base(id); body.put("error", Map.of("code", code, "message", message)); return body; }
    private static Map<String, Object> base(JsonNode id) { Map<String, Object> body = new LinkedHashMap<>(); body.put("jsonrpc", "2.0"); body.put("id", id); return body; }
}
