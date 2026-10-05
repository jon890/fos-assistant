package com.bifos.assistant.mcp.presentation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** MCP 도구가 받는 JSON 인자 모양과 하위 에이전트 session 등록의 응답 모양을 한곳에 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class McpDtos {
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

    /**
     * {@code follow_up_propose} 의 인자다. 모양 검사를 마친 인자에서 만든다.
     *
     * @param dueAt 기한 글. 없거나 {@code null} 이면 null
     * @param waiting 기다리는 중인가. 없거나 {@code null} 이면 null
     */
    public record FollowUpProposeArguments(String title, String dueAt, Boolean waiting) {
        /** JSON 의 snake_case 이름을 Java record 의 이름으로 바꾼다. {@code null} 값은 없는 것으로 본다. */
        static FollowUpProposeArguments from(JsonNode arguments) {
            JsonNode dueAt = arguments.get("due_at");
            JsonNode waiting = arguments.get("waiting");
            return new FollowUpProposeArguments(
                    arguments.get("title").asString(),
                    dueAt == null || dueAt.isNull() ? null : dueAt.asString(),
                    waiting == null || waiting.isNull() ? null : waiting.booleanValue());
        }

        /** 제목을 빼고 낸다. 제목을 로그에 남기지 않는다. */
        @Override
        public String toString() {
            return "FollowUpProposeArguments[dueAt=" + dueAt + ", waiting=" + waiting + "]";
        }
    }

    /** 하위 에이전트 session 등록의 응답이다. {@code created} 나 {@code exists} 다. */
    public record SubagentRegistrationResponse(String result) {}
}
