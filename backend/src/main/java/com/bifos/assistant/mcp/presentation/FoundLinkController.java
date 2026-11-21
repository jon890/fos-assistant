package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.FoundLinkService;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * profile 의 hook 이 비서의 웹 도구 결과에서 뽑은 주소를 알리는 경로다(ADR-20261121 / found-link-image-source).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「찾은 주소를 알린다」 가 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이다.
 */
@RestController
@RequiredArgsConstructor
public class FoundLinkController {
    private final FoundLinkService links;


    @PostMapping("/internal/hermes/found-links")
    public ResponseEntity<Void> record(@AuthenticationPrincipal Object principal, @RequestBody(required = false) JsonNode body) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
        }
        links.record(mcp, body);
        return ResponseEntity.noContent().build();
    }
}
