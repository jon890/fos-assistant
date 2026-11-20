package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.shared.util.ToolFailureText;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** JSON-RPC 응답 본문을 만든다. MCP 도구 handler 들이 같은 모양으로 답하게 한 곳에 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class McpRpc {
    static final ToolFailureText INVALID_ARGUMENTS = ToolFailureText.alternative(
        "인자 형식이 올바르지 않습니다.", "도구 설명의 인자 형식에 맞게 고쳐 다시 부른다");


    /** 모델이 받는 인자 오류다. 끝 줄이 있는 글만 받는다(docs/features/tool-fallback/spec.md 의 REQ3). */
    static Map<String, Object> invalidParams(JsonNode id, ToolFailureText reason) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", -32602, "message", "Invalid params", "data", reason.text()));
        return body;
    }


    static Map<String, Object> response(JsonNode id, Map<String, Object> result) {
        Map<String, Object> body = base(id);
        body.put("result", result);
        return body;
    }


    static Map<String, Object> error(JsonNode id, int code, ToolFailureText message) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", code, "message", message.text()));
        return body;
    }


    /** 본문을 읽지 못한 요청의 답이다. 도구 호출에 이르지 못해 모델에게 가지 않으므로 끝 줄을 붙이지 않는다. */
    static Map<String, Object> parseError() {
        Map<String, Object> body = base(null);
        body.put("error", Map.of("code", -32700, "message", "Parse error"));
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
