package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.application.LinkImageService;
import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSource;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.application.dto.InspectedImage;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.ConversationLinks;
import com.bifos.assistant.chat.conversation.application.FoundLinks;
import com.bifos.assistant.mcp.application.dto.GeneratedImageRequest;
import com.bifos.assistant.mcp.application.dto.ImageSourceRef;
import com.bifos.assistant.mcp.application.dto.ImageSourceResult;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.net.URI;
import java.util.Set;
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
 * <p>사진은 원본 조회의 개요와 같은 판정과 읽기({@link AttachmentService#inspectOverview})로 원본 바이트를 그대로 받는다.
 *
 * <p>찾지 못한 까닭은 {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 과 {@link ErrorCode#IMAGE_SOURCE_GONE} 둘로만 나눈다. 404 는 내지 않는다.
 *
 * <p>링크({@code link:})는 요청자가 그 대화에 보낸 메시지에 있는 주소일 때만 바깥에서 받는다(ADR-20261120 / link-image-source).
 * 사용자 메시지에 없으면 비서가 그 대화에서 웹 검색과 페이지 읽기로 찾은 주소인지 한 번 더 본다(ADR-20261121 / found-link-image-source).
 * 둘 다 아닌 주소로는 바깥 요청을 한 번도 보내지 않는다. 받지 못한 까닭은 {@code IMAGE_SOURCE_LINK_*} 로 그대로 낸다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageSourceService {
    /** 호출 맥락을 서명할 때 넣는 도구 이름이다. fos-ctx 가 이 이름으로 맥락을 만든다. */
    public static final String TOOL = "image_generate";
    /** 바꾸지 않고 그대로 내는 코드다. 링크의 까닭은 모델이 사용자에게 알릴 수 있게 나눠 둔다. */
    private static final Set<ErrorCode> PASS_THROUGH = Set.of(
        ErrorCode.IMAGE_SOURCE_UNKNOWN,
        ErrorCode.IMAGE_SOURCE_GONE,
        ErrorCode.IMAGE_SOURCE_LINK_BLOCKED,
        ErrorCode.IMAGE_SOURCE_LINK_NO_IMAGE,
        ErrorCode.IMAGE_SOURCE_LINK_TOO_LARGE,
        ErrorCode.IMAGE_SOURCE_LINK_FAILED);

    private final McpCallerResolver callers;
    private final AttachmentService attachments;
    private final GeneratedImageService images;
    private final ConversationAccess access;
    private final ConversationLinks links;
    private final FoundLinks foundLinks;
    private final LinkImageService linkImages;


    /**
     * @throws ApiException {@link ErrorCode#MCP_CALL_CONTEXT_INVALID} 는 맥락이 맞지 않거나 대화 없는 실행일 때,
     * {@link ErrorCode#IMAGE_SOURCE_UNKNOWN} 은 그 대화에 없는 것을 가리켰을 때, {@link ErrorCode#IMAGE_SOURCE_GONE} 은 지웠거나 보관
     * 기간이 지났을 때다
     */
    public ImageSourceResult read(McpPrincipal principal, JsonNode body) {
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
                return new ImageSourceResult(
                    attachments.inspectOverview(caller.user(), origin.conversationId(), ref.attachmentId()), null, false);
            }
            access.requireOwn(caller.user(), origin.conversationId());
            if (ref.link() != null) {
                return readLink(caller, origin.conversationId(), ref.link());
            }
            GeneratedImageSource image = images.readByTitle(origin.conversationId(), ref.title());
            return new ImageSourceResult(new InspectedImage(image.contentType(), image.bytes()), null, false);
        } catch (ApiException ex) {
            throw translate(ex);
        }
    }


    /**
     * 사용자가 보낸 링크를 먼저 보고, 아니면 비서가 찾은 주소를 본다. 사용자가 보낸 주소를 비서가 다시 찾았어도 사용자가 보낸 링크다.
     * 찾은 주소로 받은 것만 「비서가 찾음」 이다(설계 agent-found-image 의 「「비서가 찾음」 표시(PR2)」).
     */
    private ImageSourceResult readLink(McpCaller caller, Long conversationId, String pointed) {
        boolean found = false;
        URI link = links.findSentLink(caller.user(), conversationId, pointed).orElse(null);
        if (link == null) {
            link = foundLinks.find(conversationId, pointed)
                .orElseThrow(() -> new ApiException(ErrorCode.IMAGE_SOURCE_UNKNOWN, "this link is not in the conversation"));
            found = true;
        }
        if (link.toASCIIString().length() > GeneratedImageRequest.MAX_LINK_CHARS) {
            // 받은 주소는 그림 올리기의 source_links 로 돌아온다. 그 상한을 넘으면 받아도 그림을 남기지 못하므로 받기 전에 막는다.
            throw new ApiException(ErrorCode.IMAGE_SOURCE_LINK_BLOCKED, "this link is too long");
        }
        return new ImageSourceResult(linkImages.read(link), link.toASCIIString(), found);
    }


    /** 저장 쪽의 예외를 두 까닭으로 줄인다. 남의 번호와 없는 번호가 같은 응답이어야 번호를 훑어 남의 것을 알아내지 못한다. */
    private static ApiException translate(ApiException ex) {
        ErrorCode code = ex.code();
        if (PASS_THROUGH.contains(code)) {
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
