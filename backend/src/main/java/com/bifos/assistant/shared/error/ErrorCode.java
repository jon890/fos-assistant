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
    /** 그 이름의 profile 이 Hermes 에 이미 있다. 덮지 않고 멈춘다. */
    HERMES_PROFILE_EXISTS(HttpStatus.CONFLICT),
    /** 그 메일 주소가 허용 목록에 이미 있다. 무엇이 겹쳤는지 화면이 말할 수 있게 따로 적는다. */
    PERSON_EMAIL_TAKEN(HttpStatus.CONFLICT),
    /** 그 profile 이름을 허용 목록이나 에이전트가 이미 쓴다. */
    PERSON_PROFILE_TAKEN(HttpStatus.CONFLICT),
    PERSON_NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * profile 을 만들다 실패해 만든 것을 되돌렸다.
     *
     * <p>되돌렸으므로 같은 이름으로 다시 시도할 수 있다. 되돌리기까지 실패한 경우는 이 코드가 아니라
     * 원래 실패한 오류가 그대로 올라오고, 되돌리기 실패는 로그에만 남는다.
     */
    HERMES_PROVISION_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_FAILED(HttpStatus.BAD_GATEWAY),
    HERMES_RUN_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    HERMES_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    /**
     * Hermes 가 동시 실행 한도에 닿아 429 로 거절했다.
     *
     * <p>공유 gateway 는 한도를 모든 profile 이 나눠 쓰므로, 한 사람이 한도를 채우면 다른 사람의
     * 대화가 이 코드로 실패한다. Hermes 에 닿지 못한 것과 원인이 달라 따로 적는다. 여기서 다시
     * 보내지 않는다. 붐비는데 다시 보내면 더 붐빈다.
     */
    HERMES_BUSY(HttpStatus.TOO_MANY_REQUESTS),
    /** 그 provider 의 계정이 전부 막혀 다음 모델로 넘어갔다. 실행 줄에 남는 이름이기도 하다. */
    PROVIDER_BLOCKED(HttpStatus.BAD_GATEWAY),
    /** 이 에이전트가 지금 쓸 수 있는 모델이 하나도 없다. 목록이 비었거나 전부 막혔다. */
    NO_MODEL_AVAILABLE(HttpStatus.CONFLICT),
    /** 자식 실행이 다시 자식을 부르려 했다. 깊이를 1로 제한한다. */
    ORCHESTRATION_DEPTH_EXCEEDED(HttpStatus.CONFLICT),
    /** 흐름의 한 단계가 정한 출력 계약을 지키지 않았다. 원문을 그대로 다음 단계로 넘기지 않는다. */
    ORCHESTRATION_CONTRACT_BROKEN(HttpStatus.BAD_GATEWAY),
    /** 흐름의 한 단계가 실패했다. 어느 단계인지는 실행 줄의 {@code error_code} 가 적는다. */
    ORCHESTRATION_STEP_FAILED(HttpStatus.BAD_GATEWAY),
    /**
     * 화면이 보고 있던 본문이 지금 본문이 아니다.
     *
     * <p>{@code SOUL.md} 에는 판 번호가 없어 값으로만 달라진 것을 안다. 쓰기 직전에 다시 읽어 화면이
     * 받아 간 지문과 다르면 거절하고, 화면이 새 본문을 다시 읽는다.
     */
    PERSONA_STALE(HttpStatus.CONFLICT),
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
