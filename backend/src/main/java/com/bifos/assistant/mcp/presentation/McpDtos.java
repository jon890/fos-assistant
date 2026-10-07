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

    /**
     * {@code memory_remember} 의 인자다. 모양 검사를 마친 인자에서 만든다(ADR-20261007 / memory-remember).
     *
     * @param evidence 사용자 메시지에서 옮긴 구절. 없거나 {@code null} 이면 null
     * @param memoryId 고칠 항목 번호. 없거나 {@code null} 이면 null
     * @param collection 둘 collection. 없거나 {@code null} 이면 null
     * @param sensitive 민감한 내용인가. 없거나 {@code null} 이면 null
     */
    public record MemoryRememberArguments(
            String title, String content, String evidence, Long memoryId, String collection, Boolean sensitive) {
        /** JSON 의 snake_case 이름을 Java record 의 이름으로 바꾼다. {@code null} 값은 없는 것으로 본다. */
        static MemoryRememberArguments from(JsonNode arguments) {
            JsonNode memoryId = arguments.get("memory_id");
            JsonNode sensitive = arguments.get("sensitive");
            return new MemoryRememberArguments(
                    arguments.get("title").asString(),
                    arguments.get("content").asString(),
                    optionalText(arguments, "evidence"),
                    memoryId == null || memoryId.isNull() ? null : memoryId.longValue(),
                    optionalText(arguments, "collection"),
                    sensitive == null || sensitive.isNull() ? null : sensitive.booleanValue());
        }

        private static String optionalText(JsonNode arguments, String name) {
            JsonNode value = arguments.get(name);
            return value == null || value.isNull() ? null : value.asString();
        }

        /** 제목, 본문, 근거를 빼고 낸다. 로그에 남기지 않는다. */
        @Override
        public String toString() {
            return "MemoryRememberArguments[memoryId=" + memoryId + ", collection=" + collection + ", sensitive="
                    + sensitive + "]";
        }
    }

    /** 하위 에이전트 session 등록의 응답이다. {@code created} 나 {@code exists} 다. */
    public record SubagentRegistrationResponse(String result) {}
}
