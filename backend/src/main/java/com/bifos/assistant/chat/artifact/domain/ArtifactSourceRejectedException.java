package com.bifos.assistant.chat.artifact.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** URI 문법 오류와 구분해 URL 정책 거절을 도구 결과로 전달한다. */
public class ArtifactSourceRejectedException extends ApiException {

    public ArtifactSourceRejectedException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
