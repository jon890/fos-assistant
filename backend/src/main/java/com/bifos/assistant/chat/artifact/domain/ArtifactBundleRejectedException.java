package com.bifos.assistant.chat.artifact.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/**
 * 묶음으로 보관할 수 없다. 외부 주소, {@code data:} 주소, 대화 폴더 밖 주소, 빠진 파일, 상한 초과가 그 까닭이다.
 *
 * <p>묶음 전체를 거절한다. 메시지에는 본문과 경로를 넣지 않는다.
 */
public class ArtifactBundleRejectedException extends ApiException {

    public ArtifactBundleRejectedException(String message) {
        super(ErrorCode.ARTIFACT_VAULT_UNSUPPORTED, message);
    }
}
