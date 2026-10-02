package com.bifos.assistant.mcp.presentation;

import tools.jackson.databind.JsonNode;

/** MCP 도구가 받는 JSON 인자 모양과 하위 에이전트 session 등록의 응답 모양을 한곳에 둔다. */
public final class McpDtos {
    private McpDtos() {}

    public record MemoryReadArguments(Long id) {}

    public record ArtifactWriteArguments(String conversationId, String path, String content, String sourceUrl) {
        /** JSON의 snake_case 이름을 Java record의 이름으로 바꾼다. */
        static ArtifactWriteArguments from(JsonNode arguments) {
            return new ArtifactWriteArguments(
                    arguments.get("conversation_id").asString(),
                    arguments.get("path").asString(),
                    optional(arguments, "content"),
                    optional(arguments, "source_url"));
        }

        private static String optional(JsonNode arguments, String name) {
            JsonNode value = arguments.get(name);
            return value == null ? null : value.asString();
        }
    }

    /** 하위 에이전트 session 등록의 응답이다. {@code created} 나 {@code exists} 다. */
    public record SubagentRegistrationResponse(String result) {}
}
