package com.bifos.assistant.shared.error;

import org.springframework.http.HttpStatus;

/** 웹 클라이언트에 돌려주는 고정 오류 코드다. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    MEMORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 없는 실행과 남의 실행을 같은 응답으로 숨긴다. 번호를 훑어 남의 것이 있는지 알아낼 수 없게 한다. */
    EXECUTION_NOT_FOUND(HttpStatus.NOT_FOUND),
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
    /** 자식 실행이 다시 자식을 부르려 했다. 깊이를 1로 제한한다. */
    ORCHESTRATION_DEPTH_EXCEEDED(HttpStatus.CONFLICT),
    /** 흐름의 한 단계가 정한 출력 계약을 지키지 않았다. 원문을 그대로 다음 단계로 넘기지 않는다. */
    ORCHESTRATION_CONTRACT_BROKEN(HttpStatus.BAD_GATEWAY),
    /** 흐름의 한 단계가 실패했다. 어느 단계인지는 실행 줄의 {@code error_code} 가 적는다. */
    ORCHESTRATION_STEP_FAILED(HttpStatus.BAD_GATEWAY),
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
