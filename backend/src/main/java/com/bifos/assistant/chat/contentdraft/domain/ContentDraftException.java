package com.bifos.assistant.chat.contentdraft.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** 권한 확인 뒤의 충돌 번호는 메시지나 로그 대신 필드로만 전달한다. */
public class ContentDraftException extends ApiException {
    private final Long currentRevision;


    public ContentDraftException(ErrorCode code, Long currentRevision) {
        super(code, "원고를 사용할 수 없습니다.");
        this.currentRevision = currentRevision;
    }


    public Long currentRevision() {
        return currentRevision;
    }
}
