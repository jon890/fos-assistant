package com.bifos.assistant.mcp.application.dto;

import java.util.Map;

/**
 * 예약 작업 도구 하나의 결과다. MCP 도구가 {@code body} 를 JSON 글로 감싸 낸다.
 *
 * @param error 하지 못했는가. 참이면 {@code body} 의 {@code status} 와 {@code message} 가 까닭이다
 */
public record TaskToolResult(boolean error, Map<String, Object> body) {

    public static TaskToolResult ok(Map<String, Object> body) {
        return new TaskToolResult(false, body);
    }


    public static TaskToolResult failed(String status, String message) {
        return new TaskToolResult(true, Map.of("status", status, "message", message));
    }
}
