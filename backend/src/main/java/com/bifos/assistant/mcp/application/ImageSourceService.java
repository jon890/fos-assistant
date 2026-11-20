package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSource;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.application.dto.InspectedImage;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.mcp.application.dto.ImageSourceRef;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 그림을 고칠 바탕으로 모델이 가리킨 사진이나 그림을 origin 실행의 대화에서 찾아 원본을 준다(ADR-20261120 / image-source).
 *
 * <p><b>요청 본문이 사용자나 대화를 정하지 못한다.</b> 서명한 호출 맥락에서 origin 실행을 찾고 그 실행의 사용자와 대화 안에서만 찾는다.
 * origin 판정은 만든 그림 올리기와 같다. 끝난 origin 도 받고 중지한 실행과 중지한 트리는 거절한다.
 *
 * <p>찾지 못한 까닭은 {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 과 {@link ErrorCode#IMAGE_SOURCE_GONE} 둘로만 나눈다. 404 는 내지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageSourceService {
    /** 호출 맥락을 서명할 때 넣는 도구 이름이다. fos-ctx 가 이 이름으로 맥락을 만든다. */
    public static final String TOOL = "image_generate";

    private final McpCallerResolver callers;
    private final AttachmentService attachments;
    private final GeneratedImageService images;
    private final ConversationAccess access;


    /**
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 는 맥락이 맞지 않거나 대화 없는 실행일 때,
     * {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 은 그 대화에 없는 것을 가리켰을 때, {@link ErrorCode#IMAGE_SOURCE_GONE} 은 지웠거나 보관
     * 기간이 지났을 때다
     */
    public InspectedImage read(McpPrincipal principal, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        McpCaller caller = callers.resolve(principal, TOOL, body.get("_fos_ctx"));
        AgentExecution origin = caller.originExecution();
        if (origin.conversationId() == null) {
            log.warn("그림 바탕 받기를 거절했다 reason={} executionId={}", "대화가 없는 실행이다", origin.id());
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        ImageSourceRef ref = ImageSourceRef.parse(body.get("source"));
        try {
            if (ref.attachmentId() != null) {
                return attachments.readSentOriginal(caller.user(), origin.conversationId(), ref.attachmentId());
            }
            access.requireOwn(caller.user(), origin.conversationId());
            GeneratedImageSource image = images.readByTitle(origin.conversationId(), ref.title());
            return new InspectedImage(image.contentType(), image.bytes());
        } catch (ApiException ex) {
            throw translate(ex);
        }
    }


    /** 저장 쪽의 예외를 두 까닭으로 줄인다. 남의 번호와 없는 번호가 같은 응답이어야 번호를 훑어 남의 것을 알아내지 못한다. */
    private static ApiException translate(ApiException ex) {
        ErrorCode code = ex.code();
        if (code == ErrorCode.IMAGE_SOURCE_UNKNOWN || code == ErrorCode.IMAGE_SOURCE_GONE) {
            return ex;
        }
        if (code.status() == HttpStatus.GONE) {
            return new ApiException(ErrorCode.IMAGE_SOURCE_GONE, "this image is no longer kept", ex);
        }
        if (code.status() == HttpStatus.NOT_FOUND || code == ErrorCode.VALIDATION_FAILED) {
            return new ApiException(ErrorCode.IMAGE_SOURCE_UNKNOWN, "this image is not in the conversation", ex);
        }
        return ex;
    }
}
