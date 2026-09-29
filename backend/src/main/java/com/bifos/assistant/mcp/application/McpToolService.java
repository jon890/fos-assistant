package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteResult;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class McpToolService {
    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private static final JsonMapper json = JsonMapper.builder().build();
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";
    private static final Set<String> SAFE_ARTIFACT_FAILURE_MESSAGES = Set.of(
            "this conversation does not exist",
            "could not store artifact",
            "artifact source download is busy",
            "artifact source download interrupted",
            "artifact source download timed out",
            "could not download artifact source",
            "artifact source host has no addresses",
            "artifact source host resolves to a non-public address",
            "could not connect to artifact source",
            "artifact source did not return success",
            "artifact source content type is invalid",
            "artifact source length is invalid",
            "artifact source length is truncated",
            "artifact source is too large",
            "artifact source URL is invalid",
            "artifact source host is not allowed");
    private final MemoryService memories;
    private final ArtifactWriteService artifacts;

    public List<Map<String, Object>> tools() {
        return List.of(
                Map.of("name", "memory_read", "description", "지금 묻는 사람의 Memory 항목 본문을 번호로 읽는다. 번호는 지시문의 색인에 있다.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer")), "required", List.of("id"))),
                Map.of("name", "artifact_write",
                        "description", "대화의 결과물에 쓸 파일을 저장한다. conversation_id에는 대화 UUID, path에는 폴더 안 상대 경로를 준다. content로 html 또는 css 본문을 쓰거나 source_url로 png, jpg, jpeg, gif, webp 이미지를 가져온다. 두 방식은 하나만 쓰며 파일 하나는 5MB를 넘을 수 없다. 같은 path는 새 내용으로 바뀐다.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "properties", Map.of(
                                        "conversation_id", Map.of("type", "string"),
                                        "path", Map.of("type", "string"),
                                        "content", Map.of("type", "string"),
                                        "source_url", Map.of("type", "string")),
                                "required", List.of("conversation_id", "path"),
                                "oneOf", List.of(
                                        Map.of("required", List.of("content"), "not", Map.of("required", List.of("source_url"))),
                                        Map.of("required", List.of("source_url"), "not", Map.of("required", List.of("content")))))));
    }

    /**
     * 요청자를 정하지 못한 호출의 도구 결과다.
     *
     * <p>서명 오류, profile 불일치, 부모 없음, 부모 둘 이상, 사용자 없음을 모두 이 결과 하나로 답한다(ADR-032).
     */
    public Map<String, Object> invalidContext() {
        return result(INVALID_CONTEXT, true);
    }

    public Map<String, Object> readMemory(McpCaller caller, Long id) {
        CurrentUser user = caller.user();
        try {
            Memory memory = memories.bodyFor(user, id);
            log.info("memory read userId={} memoryId={} executionId={}", user.id(), id, caller.executionId());
            return result(memory.content(), false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_NOT_FOUND) {
                return result("Memory 항목을 읽을 수 없습니다.", true);
            }
            throw ex;
        }
    }

    public Map<String, Object> writeArtifact(McpCaller caller, ArtifactWriteRequest request) {
        CurrentUser user = caller.user();
        try {
            ArtifactWriteResult written = artifacts.write(user, request);
            return result(toJson(written), false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                throw ex;
            }
            log.warn("artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                    user.id(), caller.executionId(), ex.getClass().getSimpleName(), ex.code(), safeArtifactFailureMessage(ex));
            return result("결과물을 저장할 수 없습니다.", true);
        } catch (RuntimeException ex) {
            log.warn("artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                    user.id(), caller.executionId(), ex.getClass().getSimpleName(), ErrorCode.INTERNAL_ERROR, "unexpected artifact write failure");
            return result("결과물을 저장할 수 없습니다.", true);
        }
    }

    private static String safeArtifactFailureMessage(ApiException exception) {
        String message = exception.getMessage();
        return message != null && SAFE_ARTIFACT_FAILURE_MESSAGES.contains(message) ? message : "artifact write failed";
    }

    private static String toJson(ArtifactWriteResult result) {
        return json.writeValueAsString(Map.of("path", result.path(), "byteSize", result.byteSize()));
    }
    private static Map<String, Object> result(String text, boolean error) { Map<String, Object> result = new LinkedHashMap<>(); result.put("content", List.of(Map.of("type", "text", "text", text))); result.put("isError", error); return result; }
}
