package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import java.util.LinkedHashMap;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftInputReader;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftImages;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftService;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftCreationContext;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftBindingChoiceException;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftException;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 현재 origin 권한으로 원고를 저장하고 읽는다. */
@Service
@RequiredArgsConstructor
public class McpContentDraftTools {
    public static final String WRITE = "content_draft_write";
    public static final String READ = "content_draft_read";
    public static final String IMAGE = "content_draft_image";
    /** 그림 제목 인자의 상한이다. 제목은 60자로 정하지만 뒤에 붙는 번호와 여유를 둔다. */
    public static final int IMAGE_TITLE_MAX = 200;
    static final String IMAGE_UNKNOWN_MESSAGE = "이 대화에서 만든 그림 가운데 그 제목이 없습니다. 실행 입력의 「이 대화에서 만든 그림」 제목을 글자 그대로 쓰고, 없으면 사용자에게 어느 그림인지 묻습니다.";
    static final String IMAGE_GONE_MESSAGE = "그 그림은 보관 기간이 지나 대화에서 지워져 원고에 넣을 수 없습니다. 원고를 바꾸지 말고 사용자에게 그 까닭을 알립니다.";
    private final ContentDraftService drafts;
    private final ContentDraftImages images;
    private final ContentDraftInputReader reader;
    private final McpCurrentCallerGuard guard;
    private final ObjectMapper json;


    public Map<String, Object> write(McpCaller caller, UUID requestId, UUID draftId, Long expectedRevision, JsonNode body) {
        return guarded(caller, () -> {
            var input = reader.read(body);
            var origin = caller.originExecution();
            if (draftId != null) {
                return drafts.revise(caller.user(), origin.conversationId(), draftId, expectedRevision, input);
            }
            var ctx = caller.context();
            var scope = new ContentDraftCreationContext(caller.user().id(), origin.conversationId(), origin.profileName(),
                origin.id(), origin.rootExecutionId() == null ? origin.id() : origin.rootExecutionId(),
                ctx.rootSessionId(), ctx.sessionId(), ctx.toolCallId());
            return drafts.createIdempotent(caller.user(), scope, requestId, input);
        });
    }


    /** 그 대화에서 만든 그림을 원고에 넣을 수 있게 사진 첨부 사본으로 꺼낸다(ADR-20261120 / generated-image-draft). */
    public Map<String, Object> image(McpCaller caller, String title) {
        return guarded(caller, () -> images.attach(caller.user(), caller.originExecution().conversationId(), title));
    }


    public Map<String, Object> read(McpCaller caller, UUID draftId, Long revision) {
        return guarded(caller, () -> drafts.read(caller.user(), caller.originExecution().conversationId(), draftId, revision));
    }


    /** 원고 snapshot 도구도 같은 사전·사후 검사와 오류 변환을 쓴다. */
    <T> Map<String, Object> guarded(McpCaller caller, Supplier<T> work) {
        try {
            guard.requireCurrent(caller);
            T result = null;
            ApiException error = null;
            try {
                result = work.get();
            } catch (ApiException ex) {
                error = ex;
            }
            // 오류의 현재 revision도 현재 권한을 확인한 뒤에만 돌려준다.
            guard.requireCurrent(caller);
            if (error == null) {
                return McpToolService.result(ExternalData.wrap(json.writeValueAsString(result)), false);
            }
            if (error instanceof ContentDraftBindingChoiceException choice) {
                // 바인딩을 정하지 못한 까닭은 모델이 다음 호출을 고치도록 고정 문구로 싣는다.
                return McpToolService.result(json.writeValueAsString(Map.of("code", error.code().name(), "message", choice.getMessage())), true);
            }
            if (error.code() == ErrorCode.VALIDATION_FAILED) {
                throw ContentDraftInputReader.invalid();
            }
            if (error.code() == ErrorCode.MCP_CALL_CONTEXT_INVALID) {
                return invalidContext();
            }
            var failure = json.createObjectNode().put("code", error.code().name());
            if (error instanceof ContentDraftException typed && typed.currentRevision() != null) {
                failure.put("currentRevision", typed.currentRevision());
            }
            if (error.code() == ErrorCode.CONTENT_DRAFT_DUPLICATE_PHOTO) {
                // 모델이 고칠 방법을 알도록 고정 문구만 싣는다. 예외 message 는 싣지 않는다.
                failure.put("message", ContentDraftInputReader.DUPLICATE_PHOTO_MESSAGE);
            } else if (error.code() == ErrorCode.IMAGE_SOURCE_UNKNOWN) {
                failure.put("message", IMAGE_UNKNOWN_MESSAGE);
            } else if (error.code() == ErrorCode.IMAGE_SOURCE_GONE) {
                failure.put("message", IMAGE_GONE_MESSAGE);
            }
            return McpToolService.result(json.writeValueAsString(failure), true);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                throw ContentDraftInputReader.invalid();
            }
            return invalidContext();
        } catch (RuntimeException ex) {
            return McpToolService.result("{\"code\":\"INTERNAL_ERROR\"}", true);
        }
    }


    private static Map<String, Object> invalidContext() {
        return McpToolService.result("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.", true);
    }


    public Map<String, Object> writeDefinition() {
        var fields = Map.<String, Object>of("requestId", uuid(), "draftId", uuid(), "expectedRevision", revision(true), "draft", draftSchema());
        var schema = object(fields, List.of("draft"));
        schema.put("oneOf", List.of(
            Map.of("required", List.of("requestId"), "not", Map.of("anyOf", List.of(
                Map.of("required", List.of("draftId")), Map.of("required", List.of("expectedRevision"))))),
            Map.of("required", List.of("draftId", "expectedRevision"), "not", Map.of("required", List.of("requestId")))));
        return Map.of("name", WRITE, "description",
            "현재 대화의 원고를 저장한다. 첫 생성은 requestId UUID를 한 번 정한다. 응답 유실 뒤 재시도는 같은 requestId와 최초 원고를 그대로 보낸다. "
                + "별도 원고 의도일 때만 새 requestId와 새 도구 호출을 쓴다. 수정은 draftId와 expectedRevision을 함께 주고 requestId는 생략한다. "
                + "사진은 다시 분석하지 않으며 충돌은 현재 revision을 읽고 판단한다. "
                + "같은 사진(assetId)은 원고 전체에서 이미지 블록 하나에만 넣는다. 두 번 넣으면 CONTENT_DRAFT_DUPLICATE_PHOTO로 거절된다.",
            "inputSchema", schema);
    }


    public Map<String, Object> readDefinition() {
        return Map.of("name", READ, "description", "현재 대화의 원고를 읽는다. revision을 생략하면 현재 판을 읽는다.",
            "inputSchema", object(Map.of("draftId", uuid(), "revision", revision(false)), List.of("draftId")));
    }


    public Map<String, Object> imageDefinition() {
        return Map.of("name", IMAGE, "description",
            "지금 대화에서 만든 그림을 블로그 원고에 넣을 사진으로 꺼낸다. title 에는 그림 제목을 글자 그대로 준다. "
                + "결과의 assetId 는 원고 사진 자리의 assetId 에, fileName 과 photoDir 은 초안의 사진 줄과 photo_dir 에 쓴다. "
                + "같은 그림을 다시 부르면 같은 값을 준다. 보관 기간이 지난 그림은 IMAGE_SOURCE_GONE 으로 거절된다.",
            "inputSchema", object(Map.of("title", text(1, IMAGE_TITLE_MAX)), List.of("title")));
    }


    private static Map<String, Object> draftSchema() {
        var observation = object(Map.of("id", assetId(), "revision", revision(false)), List.of("id", "revision"));
        var paragraph = object(Map.of("type", Map.of("const", "paragraph"), "text", text(0, 20_000)), List.of("type", "text"));
        var image = object(Map.of("type", Map.of("const", "image"), "assetId", assetId(), "caption", text(0, 300),
            "observationRef", observation), List.of("type", "assetId"));
        var sticker = object(Map.of("type", Map.of("const", "sticker"), "code",
            Map.of("type", "string", "minLength", 1, "maxLength", 64, "pattern", "^[A-Za-z0-9_-]+$")), List.of("type", "code"));
        var map = object(Map.of("type", Map.of("const", "map"), "name", text(1, 200), "address", text(1, 200)), List.of("type", "name", "address"));
        return object(Map.of("title", text(1, 100), "category", text(1, 50),
            "tags", Map.of("type", "array", "maxItems", 30, "items", text(1, 30)),
            "blocks", Map.of("type", "array", "maxItems", 500, "items", Map.of("oneOf", List.of(paragraph, image, sticker, map)))),
            List.of("title", "category", "tags", "blocks"));
    }


    private static Map<String, Object> assetId() {
        return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$");
    }


    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "format", "uuid", "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }


    private static Map<String, Object> revision(boolean writing) {
        return Map.of("type", "integer", "minimum", 1, "maximum", ContentDraftInputReader.MAX_REVISION - (writing ? 1 : 0));
    }


    private static Map<String, Object> text(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }


    private static LinkedHashMap<String, Object> object(Map<String, Object> properties, List<String> required) {
        return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required));
    }
}
