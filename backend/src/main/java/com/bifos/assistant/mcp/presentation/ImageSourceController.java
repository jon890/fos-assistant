package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.chat.attachment.application.dto.InspectedImage;
import com.bifos.assistant.mcp.application.ImageSourceService;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * profile 의 hook 이 그림을 고칠 바탕 사진을 받아 가는 경로다(ADR-20261120 / image-source).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「그림을 고칠 사진을 받는다」 가 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이다.
 * <b>이 경로는 404 를 내지 않는다.</b> hook 이 404 를 이 경로가 없는 옛 Control Plane 으로 읽는다.
 */
@RestController
@RequiredArgsConstructor
public class ImageSourceController {
    private final ImageSourceService sources;


    @PostMapping("/internal/hermes/image-source")
    public ResponseEntity<byte[]> read(@AuthenticationPrincipal Object principal, @RequestBody(required = false) JsonNode body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        InspectedImage image = sources.read(mcp, body);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(image.contentType()))
            .contentLength(image.bytes().length)
            .body(image.bytes());
    }
}
