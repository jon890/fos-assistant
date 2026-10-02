package com.bifos.assistant.shared.auth;

import java.util.Optional;

/**
 * 웹 토큰이 가리키는 주소를 현재 사용자로 바꾼다.
 *
 * <p>꺼진 주소면 비어 있는 값이다. 구현은 {@code user.application} 에 있다. {@code shared} 가 {@code user} 를
 * 쓰지 않도록 필터가 받는 모양만 여기 둔다(ADR-068).
 */
public interface TokenUserResolver {
    Optional<CurrentUser> resolveCurrentUser(String email, String displayName);
}
