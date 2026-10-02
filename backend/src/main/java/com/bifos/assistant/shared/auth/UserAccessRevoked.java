package com.bifos.assistant.shared.auth;

/** 관리자가 허용 목록에서 사용자를 껐다. 그 사용자가 가진 것을 거둘 쪽이 받는다(ADR-056). */
public record UserAccessRevoked(Long userId) {}
