package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.chat.artifact.application.dto.GeneratedImageSaved;
import com.bifos.assistant.mcp.application.GeneratedImageUploadService;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * profile 의 hook 이 비서가 만든 그림을 올리는 경로다(ADR-20261119 / generated-image).
 *
 * <p>계약은 {@code hermes/plugins/fos-ctx/README.md} 의 「만든 그림을 올린다」 가 갖는다. 인증은 {@code /mcp} 와 같은 profile 토큰이다.
 * 그림은 JSON 의 base64 가 아니라 multipart 의 {@code image} 조각으로 받는다.
 *
 * <p><b>이 경로는 404 를 내지 않는다.</b> hook 이 404 를 이 경로가 없는 옛 Control Plane 으로 읽고 모델에게 가는 결과를 바꾸지 않기
 * 때문이다. 그러면 답에 Hermes 쪽 파일 경로가 남는다. 저장 중의 {@code *_NOT_FOUND} 는 500 으로 바꿔 답한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GeneratedImageController {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GeneratedImageUploadService uploads;


    @PostMapping(path = "/internal/hermes/generated-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GeneratedImageSaved upload(
        @AuthenticationPrincipal Object principal,
        @RequestPart(name = "meta", required = false) String meta,
        @RequestPart(name = "image", required = false) MultipartFile image) {
        if (!(principal instanceof McpPrincipal mcp)) {
            throw invalid();
        }
        if (meta == null || image == null) {
            throw invalid();
        }
        try {
            return uploads.upload(mcp, read(meta), image.getBytes());
        } catch (ApiException ex) {
            if (ex.code().status() == HttpStatus.NOT_FOUND) {
                log.warn("만든 그림을 저장하지 못했다 profile={} code={}", mcp.profileName(), ex.code());
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not store the generated image", ex);
            }
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not read the generated image", ex);
        }
    }


    private static JsonNode read(String meta) {
        try {
            return JSON.readTree(meta);
        } catch (JacksonException ex) {
            throw invalid();
        }
    }


    private static ApiException invalid() {
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }
}
