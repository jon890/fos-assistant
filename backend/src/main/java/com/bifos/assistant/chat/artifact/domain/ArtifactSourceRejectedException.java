package com.bifos.assistant.chat.artifact.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** URI 문법 오류와 구분해 URL 정책 거절을 도구 결과로 전달한다. */
public class ArtifactSourceRejectedException extends ApiException {

    private final String reason;


    public ArtifactSourceRejectedException(String reason) {
        super(ErrorCode.VALIDATION_FAILED, "allowed_host".equals(reason)
            ? "artifact source host is not allowed"
            : "artifact source URL is invalid");
        this.reason = reason;
    }


    public String reason() {
        return reason;
    }
}
