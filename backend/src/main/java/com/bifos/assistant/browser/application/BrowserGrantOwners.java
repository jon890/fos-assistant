package com.bifos.assistant.browser.application;

import java.util.Optional;

/**
 * 중계의 바인딩 표식이 가리키는 브라우저 주인을 찾는다.
 *
 * <p>바인딩은 {@code connector} 가 갖는다. {@code browser} 가 그 패키지보다 아래라 이 인터페이스를 두고 {@code connector} 가
 * 구현한다.
 */
public interface BrowserGrantOwners {

    /** 그 바인딩의 연결 주인(사용자 번호)이다. 바인딩이 없으면 비어 있다. */
    Optional<Long> bindingOwner(long bindingId);
}
