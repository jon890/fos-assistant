package com.bifos.assistant.connector.presentation.contentjob;

import com.bifos.assistant.connector.application.ContentDraftJobAuthorizer;
import com.bifos.assistant.connector.presentation.contentjob.ContentDraftJobAuthorizeDtos.AuthorizeRequest;
import com.bifos.assistant.connector.presentation.contentjob.ContentDraftJobAuthorizeDtos.AuthorizeResponse;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정확한 내부 경로에서만 본문의 원고 저장 ticket 으로 인증한다. 사용자 JWT 는 받지 않는다. */
@RestController
@RequiredArgsConstructor
public class ContentDraftJobAuthorizeController {
    private final ContentDraftJobAuthorizer authorizer;

    @PostMapping(value = "/internal/content-draft-jobs/authorize", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthorizeResponse authorize(HttpServletRequest request) throws IOException {
        // Content-Length가 없거나 틀려도 실제 받은 바이트로 상한을 지킨다.
        byte[] raw = request.getInputStream().readNBytes(ContentDraftJobAuthorizeDtos.REQUEST_LIMIT + 1);
        if (raw.length > ContentDraftJobAuthorizeDtos.REQUEST_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "content save authorize request rejected");
        }
        AuthorizeRequest body = AuthorizeRequest.decode(raw);
        authorizer.authorize(body.ticket(), body.stage(), body.position());
        return AuthorizeResponse.ALLOWED;
    }
}
