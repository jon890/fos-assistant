package com.bifos.assistant.browser.domain;

/**
 * 사용자 브라우저의 프로필 디렉터리다. 로그인 세션은 이 디렉터리에만 남는다.
 *
 * <p>키가 소문자 16진수 64자리가 아니면 {@link IllegalArgumentException} 으로 거절한다.
 */
public interface BrowserProfileStore {

    /** 없으면 주인만 읽고 쓰는 디렉터리를 만든다. 브라우저 설정 파일이 없으면 이전 세션을 이어서 여는 설정을 써 둔다. */
    void ensure(String profileKey);

    /** 링크를 따라가지 않고 디렉터리를 통째로 지운다. 없으면 아무것도 하지 않는다. */
    void delete(String profileKey);
}
