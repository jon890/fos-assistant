package com.bifos.assistant.shared.auth;

/** 그 사용자가 지금도 들어올 수 있는가. 로그인 판정과 같은 답을 낸다(ADR-056). */
public interface UserAccessPolicy {

    boolean allowed(Long userId);


    /**
     * 이미 그 사용자 행을 잠근 트랜잭션에 참가해 허용 목록 행을 쓰기 잠금으로 읽고 판정한다.
     *
     * <p>허용 목록을 끄는 쓰기와 같은 행을 기다리므로, 끄기가 먼저 커밋됐으면 거짓이다. 잠금을 모르는 구현은 판정하지 않는다.
     */
    default boolean allowedForUpdate(Long userId) {
        throw new UnsupportedOperationException("this access policy cannot lock the allow list");
    }
}
