package com.bifos.assistant.shared.auth;

/** 그 사용자가 지금도 들어올 수 있는가. 로그인 판정과 같은 답을 낸다(ADR-056). */
public interface UserAccessPolicy {

    boolean allowed(Long userId);
}
