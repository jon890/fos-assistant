package com.bifos.assistant.user.application;

import com.bifos.assistant.user.domain.AppUser;

/**
 * 사용자가 처음 만들어진 순간을 듣는다.
 *
 * <p>사용자를 새로 저장한 바로 뒤, 같은 트랜잭션에서 불린다. {@code email} 은 로그인 판정에 쓴 주소
 * 그대로다. 여기서 던진 예외는 사용자 저장까지 함께 되돌린다.
 */
public interface FirstSignInListener {

    void onUserCreated(AppUser created, String email);
}
