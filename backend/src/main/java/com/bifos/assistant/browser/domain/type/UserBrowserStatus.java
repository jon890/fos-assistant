package com.bifos.assistant.browser.domain.type;

/**
 * 사용자 브라우저의 상태다. {@code user_browser.status} 가 이 이름을 저장한다. 전이는 {@code docs/features/user-browser.md} 의
 * 「상태 전이」 가 갖는다.
 */
public enum UserBrowserStatus {
    /** 컨테이너가 없다. 프로필 디렉터리만 있다. */
    STOPPED,
    /** 컨테이너를 만들고 CDP 가 답하기를 기다린다. */
    STARTING,
    /** CDP 가 답한다. */
    RUNNING,
    /** 컨테이너를 멈추고 지운다. */
    STOPPING,
    /** 켜거나 멈추다 실패했다. 까닭은 {@code last_error} 에 남는다. */
    FAILED
}
