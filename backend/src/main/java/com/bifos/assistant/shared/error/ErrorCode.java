package com.bifos.assistant.shared.error;

import org.springframework.http.HttpStatus;

/** 웹 클라이언트에 돌려주는 고정 오류 코드다. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    MEMORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    MEMORY_SCOPE_REQUIRED(HttpStatus.BAD_REQUEST),
    AGENT_DISABLED(HttpStatus.CONFLICT),
    AGENT_MODEL_UNKNOWN(HttpStatus.BAD_GATEWAY),
    /** 호출자에게 연결한 Hermes profile이 없다. 다른 사용자의 profile을 빌리지 않는다. */
    HERMES_BINDING_MISSING(HttpStatus.CONFLICT),
    /** 바인딩은 있지만 이 호스트에 API key가 준비되지 않았다. */
    HERMES_PROFILE_KEY_MISSING(HttpStatus.CONFLICT),
    HERMES_BINDING_DISABLED(HttpStatus.CONFLICT),
    HERMES_RUN_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    HERMES_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
