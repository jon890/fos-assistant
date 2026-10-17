package com.bifos.assistant.connector.contentdraft.application;

/**
 * 원고 저장 잠금 안에서 현재 권한이 맞지 않아 그 트랜잭션을 되돌리고 끝낸다는 표시다.
 *
 * <p>잠금 안의 참가 메서드가 던진 예외는 트랜잭션을 되돌림 전용으로 만든다. 거절 상태는 새 트랜잭션에서 커밋한다.
 */
class ContentDraftRefusal extends RuntimeException {
    static final String EXPIRED = "expired";

    private final String code;


    ContentDraftRefusal(String code) {
        super(code, null, false, false);
        this.code = code;
    }


    String code() {
        return code;
    }
}
