package com.bifos.assistant.shared.auth;

/** 관리자가 허용 목록에서 사용자를 껐다. 브라우저 정리가 커밋 뒤에 받는다(ADR-059). */
public record UserAccessRevoked(Long userId) {
}
