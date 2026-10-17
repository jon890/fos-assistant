package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * tools/call 의 {@code result} 가 Hermes 가 받는 MCP {@code CallToolResult} 모양인지 본다.
 *
 * <p>Hermes 는 결과를 {@code mcp.types.CallToolResult} 로 검증하고 {@code content} 가 없으면 버린다. 판정이 실제 mcp 패키지와 같은지는
 * {@link McpToolResultShapeTest} 와 {@code hermes/tests/test_control_plane_tool_result.py} 가 같은 fixture 로 확인한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class McpToolResultShape {
    static final Path FIXTURE = Path.of("../test/fixtures/mcp-call-tool-result-v1.json");
    /** 이 Control Plane 이 내는 내용 블록이다. 다른 종류를 내기 시작하면 그 필수 칸을 여기에 더한다. */
    private static final Set<String> CONTENT_TYPES = Set.of("text");


    static boolean accepts(JsonNode result) {
        if (result == null || !result.isObject()) {
            return false;
        }
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            return false;
        }
        for (JsonNode item : content) {
            if (!item.isObject() || !item.path("type").isString() || !CONTENT_TYPES.contains(item.path("type").stringValue())
                || !item.path("text").isString()) {
                return false;
            }
        }
        JsonNode isError = result.get("isError");
        JsonNode structured = result.get("structuredContent");
        return (isError == null || isError.isBoolean()) && (structured == null || structured.isObject());
    }


    static void assertCallToolResult(String tool, JsonNode result) {
        assertThat(accepts(result)).as("%s 의 결과가 CallToolResult 모양이 아니다: %s", tool, result).isTrue();
    }


    static JsonNode fixture() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(FIXTURE));
    }
}
