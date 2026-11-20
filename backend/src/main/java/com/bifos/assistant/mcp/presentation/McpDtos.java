package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.chat.attachment.application.MediaObservationInputReader;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftInputReader;
import com.bifos.assistant.mcp.application.McpContentDraftTools;
import com.bifos.assistant.mcp.application.McpFinancialLimitTools;
import com.bifos.assistant.memory.application.dto.MemorySearchCursor;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** MCP 도구가 받는 JSON 인자 모양과 하위 에이전트 session 등록의 응답 모양을 한곳에 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class McpDtos {
    public record ContentDraftWriteArguments(UUID requestId, UUID draftId, Long expectedRevision, JsonNode draft) {
        static ContentDraftWriteArguments from(JsonNode node) {
            ContentDraftInputReader.fields(node, Set.of("draft"), Set.of("requestId", "draftId", "expectedRevision"));
            boolean modifying = node.has("draftId");
            if (modifying != node.has("expectedRevision") || modifying == node.has("requestId")) {
                throw ContentDraftInputReader.invalid();
            }
            Long revision = modifying ? ContentDraftInputReader.revision(node.get("expectedRevision")) : null;
            if (revision != null && revision == ContentDraftInputReader.MAX_REVISION) {
                throw ContentDraftInputReader.invalid();
            }
            return new ContentDraftWriteArguments(modifying ? null : draftUuid(node.get("requestId")),
                modifying ? draftUuid(node.get("draftId")) : null, revision, node.get("draft"));
        }


        @Override
        public String toString() {
            return "ContentDraftWriteArguments[body omitted]";
        }
    }

    public record ContentDraftReadArguments(UUID draftId, Long revision) {
        static ContentDraftReadArguments from(JsonNode node) {
            ContentDraftInputReader.fields(node, Set.of("draftId"), Set.of("revision"));
            return new ContentDraftReadArguments(draftUuid(node.get("draftId")),
                node.has("revision") ? ContentDraftInputReader.revision(node.get("revision")) : null);
        }
    }

    /** 원고 ID·revision 과 선택 바인딩 번호만 받는다. 소유자·profile·경로는 받지 않는다. */
    public record ContentDraftPreviewArguments(UUID draftId, long revision, Long bindingId) {
        static ContentDraftPreviewArguments from(JsonNode node) {
            ContentDraftInputReader.fields(node, Set.of("draftId", "revision"), Set.of("bindingId"));
            JsonNode binding = node.get("bindingId");
            if (binding != null && (!binding.isIntegralNumber() || !binding.canConvertToLong() || binding.longValue() < 1)) {
                throw ContentDraftInputReader.invalid();
            }
            return new ContentDraftPreviewArguments(draftUuid(node.get("draftId")),
                ContentDraftInputReader.revision(node.get("revision")), binding == null ? null : binding.longValue());
        }
    }

    /** 그림 제목 하나만 받는다. 대화와 사용자는 서명한 호출 맥락이 정한다(ADR-20261120 / generated-image-draft). */
    public record ContentDraftImageArguments(String title) {
        static ContentDraftImageArguments from(JsonNode node) {
            ContentDraftInputReader.fields(node, Set.of("title"), Set.of());
            JsonNode title = node.get("title");
            if (!title.isString() || title.asString().isBlank()
                || title.asString().codePointCount(0, title.asString().length()) > McpContentDraftTools.IMAGE_TITLE_MAX) {
                throw ContentDraftInputReader.invalid();
            }
            return new ContentDraftImageArguments(title.asString().strip());
        }
    }

    public record ContentDraftSaveArguments(UUID snapshotId) {
        static ContentDraftSaveArguments from(JsonNode node) {
            ContentDraftInputReader.fields(node, Set.of("snapshotId"), Set.of());
            return new ContentDraftSaveArguments(draftUuid(node.get("snapshotId")));
        }
    }

    /** 이어 가기는 인자가 없다. 모르는 키는 거절한다. */
    public record ApprovalResumeArguments() {
        static ApprovalResumeArguments from(JsonNode node) {
            if (node == null || !node.isObject() || !node.propertyNames().isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid approval resume arguments");
            }
            return new ApprovalResumeArguments();
        }
    }

    /** 인자가 없다. 모르는 키는 거절한다. */
    public record OrderLimitReadArguments() {
        static OrderLimitReadArguments from(JsonNode node) {
            if (node == null || !node.isObject() || !node.propertyNames().isEmpty()) {
                throw invalidOrderLimit();
            }
            return new OrderLimitReadArguments();
        }
    }

    /** 이름은 다섯 상한 가운데 하나이고 값은 0 이상, 소수 6자리까지의 십진 글이다. */
    public record OrderLimitChangeArguments(String name, String value) {
        static OrderLimitChangeArguments from(JsonNode node) {
            if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of("name", "value"))
                || !node.get("name").isString() || !node.get("value").isString()) {
                throw invalidOrderLimit();
            }
            String name = node.get("name").stringValue();
            String value = node.get("value").stringValue();
            if (!McpFinancialLimitTools.NAMES.contains(name) || !value.matches(McpFinancialLimitTools.DECIMAL)) {
                throw invalidOrderLimit();
            }
            return new OrderLimitChangeArguments(name, value);
        }
    }


    private static ApiException invalidOrderLimit() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "invalid order limit arguments");
    }


    private static UUID draftUuid(JsonNode node) {
        if (node == null || !node.isString()) {
            throw ContentDraftInputReader.invalid();
        }
        return ContentDraftInputReader.uuid(node.stringValue());
    }


    public record MediaObservationListArguments(String afterAssetId, int limit) {
        static MediaObservationListArguments from(JsonNode node) {
            MediaObservationInputReader.fields(node, Set.of("afterAssetId", "limit"));
            JsonNode cursor = node.get("afterAssetId");
            if (cursor != null && !cursor.isNull() && !cursor.isTextual()) {
                throw MediaObservationInputReader.invalid();
            }
            String after = cursor == null || cursor.isNull() ? null : cursor.asString();
            if (after != null) {
                MediaObservationInputReader.assetId(after);
            }
            JsonNode limit = node.get("limit");
            long value = limit == null || limit.isNull() ? 30 : MediaObservationInputReader.revision(limit);
            if (value < 1 || value > 30) {
                throw MediaObservationInputReader.invalid();
            }
            return new MediaObservationListArguments(after, (int)value);
        }
    }

    public record MediaObservationRecordArguments(
        String assetId, long expectedRevision, UUID requestId, JsonNode observation) {
        static MediaObservationRecordArguments from(JsonNode node) {
            MediaObservationInputReader.fields(node, Set.of("assetId", "expectedRevision", "requestId", "observation"));
            JsonNode asset = node.get("assetId");
            if (asset == null || !asset.isTextual()) {
                throw MediaObservationInputReader.invalid();
            }
            MediaObservationInputReader.assetId(asset.asString());
            return new MediaObservationRecordArguments(
                asset.asString(),
                MediaObservationInputReader.revision(node.get("expectedRevision")),
                MediaObservationInputReader.requestId(node.get("requestId")),
                node.get("observation"));
        }


        @Override
        public String toString() {
            return "MediaObservationRecordArguments[body omitted]";
        }
    }

    public record MemoryReadArguments(Long id) {
    }

    /** 검색어를 로그에 남기지 않는 기억 검색 인자다. 제목과 일반 본문을 관련도 순으로 찾는다. */
    public record MemorySearchArguments(String query, int limit, MemorySearchCursor cursor) {
        /**
         * 질문이 비었거나 200자를 넘거나, 커서를 읽을 수 없거나 다른 질문의 커서면 빈 값이다. 커서가 없으면 첫 페이지다.
         */
        static Optional<MemorySearchArguments> from(JsonNode arguments) {
            String query = arguments.get("query").asString().strip();
            if (query.isEmpty() || query.codePointCount(0, query.length()) > 200) {
                return Optional.empty();
            }
            JsonNode limit = arguments.get("limit");
            JsonNode cursor = arguments.get("cursor");
            Optional<MemorySearchCursor> position = cursor == null
                ? Optional.of(MemorySearchCursor.first(query))
                : MemorySearchCursor.parse(cursor.asString()).filter(parsed -> parsed.matches(query));
            return position.map(value -> new MemorySearchArguments(query, limit == null ? 10 : limit.intValue(), value));
        }


        @Override
        public String toString() {
            return "MemorySearchArguments[limit=" + limit + ", offset=" + cursor.offset() + "]";
        }
    }

    public record ArtifactWriteArguments(
        String conversationId, String path, String content, String sourceUrl, String title, String description) {
        /** JSON의 snake_case 이름을 Java record의 이름으로 바꾼다. 선택 인자의 {@code null} 은 없는 것으로 본다. */
        static ArtifactWriteArguments from(JsonNode arguments) {
            return new ArtifactWriteArguments(
                arguments.get("conversation_id").asString(),
                arguments.get("path").asString(),
                optional(arguments, "content"),
                optional(arguments, "source_url"),
                optional(arguments, "title"),
                optional(arguments, "description"));
        }


        private static String optional(JsonNode arguments, String name) {
            JsonNode value = arguments.get(name);
            return value == null || value.isNull() ? null : value.asString();
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
     * @param archivable 자동 보관을 허락하는가. 없거나 {@code null} 이면 null
     * @param replaces 새 기억이 대신할 옛 기억 번호. 없거나 {@code null} 이면 빈 목록
     */
    public record MemoryRememberArguments(
        String title,
        String content,
        String evidence,
        Long memoryId,
        String collection,
        Boolean sensitive,
        Boolean archivable,
        String summary,
        List<Long> replaces) {


        /** {@code replaces} 에 줄 수 있는 번호 수의 상한이다. */
        static final int REPLACES_MAX = 10;

        /** 받는 인자 이름이다. 사용자와 범위, 바로 저장할지를 정하는 인자는 없다. */
        static final Set<String> FIELDS = Set.of(
            "title", "content", "evidence", "memory_id", "collection", "sensitive", "archivable", "summary", "replaces");

        /** JSON 의 snake_case 이름을 Java record 의 이름으로 바꾼다. {@code null} 값은 없는 것으로 본다. */
        static MemoryRememberArguments from(JsonNode arguments) {
            JsonNode memoryId = arguments.get("memory_id");
            JsonNode sensitive = arguments.get("sensitive");
            JsonNode archivable = arguments.get("archivable");
            return new MemoryRememberArguments(
                arguments.get("title").asString(),
                arguments.get("content").asString(),
                optionalText(arguments, "evidence"),
                memoryId == null || memoryId.isNull() ? null : memoryId.longValue(),
                optionalText(arguments, "collection"),
                sensitive == null || sensitive.isNull() ? null : sensitive.booleanValue(),
                archivable == null || archivable.isNull() ? null : archivable.booleanValue(),
                optionalText(arguments, "summary"),
                replaces(arguments.get("replaces")));
        }


        /**
         * {@code replaces} 의 모양이 맞는가. 없거나 {@code null} 이거나, 1개부터 {@link #REPLACES_MAX} 개까지의 중복 없는 정수 배열이다.
         * {@code memory_id} 와 함께 주면 어긋난다. 합치기는 새 기억을 만드는 길이다.
         */
        static boolean validReplaces(JsonNode arguments) {
            JsonNode value = arguments.get("replaces");
            if (value == null || value.isNull()) {
                return true;
            }
            JsonNode memoryId = arguments.get("memory_id");
            if (memoryId != null && !memoryId.isNull()) {
                return false;
            }
            if (!value.isArray() || value.isEmpty() || value.size() > REPLACES_MAX) {
                return false;
            }
            Set<Long> seen = new HashSet<>();
            for (JsonNode node : value) {
                if (!node.isIntegralNumber() || !node.canConvertToLong() || !seen.add(node.longValue())) {
                    return false;
                }
            }
            return true;
        }


        private static List<Long> replaces(JsonNode value) {
            if (value == null || value.isNull()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            value.forEach(node -> ids.add(node.longValue()));
            return List.copyOf(ids);
        }


        private static String optionalText(JsonNode arguments, String name) {
            JsonNode value = arguments.get(name);
            return value == null || value.isNull() ? null : value.asString();
        }


        /** 제목, 본문, 근거를 빼고 낸다. 로그에 남기지 않는다. */
        @Override
        public String toString() {
            return "MemoryRememberArguments[memoryId=" + memoryId + ", collection=" + collection + ", sensitive="
                + sensitive + ", archivable=" + archivable + ", replaces=" + replaces + "]";
        }
    }

    /** 도구 판정의 답이다. 모양은 플러그인 정책 판정과 같다(ADR-20261105 / event-watch). */
    public record ToolGuardResponse(String decision, String message) {
        public static ToolGuardResponse allow() {
            return new ToolGuardResponse("allow", null);
        }


        public static ToolGuardResponse block(String message) {
            return new ToolGuardResponse("block", message);
        }
    }

    /** 하위 에이전트 session 등록의 응답이다. {@code created} 나 {@code exists} 다. */
    public record SubagentRegistrationResponse(String result) {
    }

    /** 하위 에이전트 끝 소식의 응답이다. {@code recorded} 나 {@code ignored} 다. */
    public record SubagentStopResponse(String result) {
    }
}
