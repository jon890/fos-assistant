package com.bifos.assistant.mcp.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** JSON-RPC 응답 본문을 만든다. MCP 도구 handler 들이 같은 모양으로 답하게 한 곳에 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class McpRpc {
    static final String INVALID_ARGUMENTS = "인자 형식이 올바르지 않습니다.";


    static Map<String, Object> invalidParams(JsonNode id, String reason) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", -32602, "message", "Invalid params", "data", reason));
        return body;
    }


    static Map<String, Object> response(JsonNode id, Map<String, Object> result) {
        Map<String, Object> body = base(id);
        body.put("result", result);
        return body;
    }


    static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", code, "message", message));
        return body;
    }


    /** 키가 있고 그 값이 글이다. */
    static boolean text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value != null && value.isTextual();
    }


    private static Map<String, Object> base(JsonNode id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        return body;
    }
}
