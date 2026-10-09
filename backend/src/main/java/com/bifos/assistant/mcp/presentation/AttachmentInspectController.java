package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.chat.application.InspectedImage;
import com.bifos.assistant.mcp.application.AttachmentInspectService;
import com.bifos.assistant.mcp.application.McpPrincipal;
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

/** profile 토큰으로 인증한 현재 실행에 원본 또는 영역 bytes를 돌려준다. */
@RestController
@RequiredArgsConstructor
public class AttachmentInspectController {
    private final AttachmentInspectService inspections;

    @PostMapping("/internal/hermes/attachment-inspect")
    public ResponseEntity<byte[]> inspect(@AuthenticationPrincipal Object principal, @RequestBody JsonNode body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        InspectedImage image = inspections.inspect(mcp, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType())).contentLength(image.bytes().length)
                .body(image.bytes());
    }
}
