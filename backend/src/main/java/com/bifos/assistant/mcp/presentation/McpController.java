package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
    private final McpToolService tools; private final CurrentUserProvider currentUser;
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
            case "initialize" -> response(id, Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of("listChanged", false)), "serverInfo", Map.of("name", "fos-assistant-memory", "version", "0.1.0-SNAPSHOT")));
            case "tools/list" -> response(id, Map.of("tools", tools.tools()));
            case "tools/call" -> call(id, request.path("params"));
            default -> error(id, -32601, "Method not found");
        });
    }
    private Map<String, Object> call(JsonNode id, JsonNode params) {
        if (!params.hasNonNull("name")
                || !params.path("arguments").has("id")
                || !params.path("arguments").path("id").isIntegralNumber()
                || !params.path("arguments").path("id").canConvertToLong()) {
            return error(id, -32602, "Invalid params");
        }
        try { return response(id, tools.call(currentUser.require(), params.path("name").asString(), params.path("arguments").path("id").longValue())); }
        catch (McpToolService.UnknownToolException ex) { return error(id, -32601, "Method not found"); }
    }
    private static Map<String, Object> response(JsonNode id, Map<String, Object> result) { Map<String, Object> body = base(id); body.put("result", result); return body; }
    private static Map<String, Object> error(JsonNode id, int code, String message) { Map<String, Object> body = base(id); body.put("error", Map.of("code", code, "message", message)); return body; }
    private static Map<String, Object> base(JsonNode id) { Map<String, Object> body = new LinkedHashMap<>(); body.put("jsonrpc", "2.0"); body.put("id", id); return body; }
}
