package com.bifos.assistant.browser.domain;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 브라우저 컨테이너를 만들고 켜고 멈추고 지운다. 구현은 브라우저 전용 Docker socket proxy 를 부른다.
 *
 * <p>실패하면 런타임 예외를 던진다. 예외 메시지에 proxy 의 응답 본문을 싣지 않는다.
 */
public interface BrowserRuntime {

    /** 그 프로필로 컨테이너를 만든다. 켜지는 않는다. */
    String create(String profileKey);

    void start(String containerId);

    /** 멈춘다. 이미 멈췄거나 없어도 성공이다. */
    void stop(String containerId);

    /** 지운다. 없어도 성공이다. */
    void remove(String containerId);

    /** 그 컨테이너의 CDP 주소다. 컨테이너가 없거나 브라우저 망의 주소가 없으면 비어 있다. */
    Optional<URI> cdpAddress(String containerId);

    /** 브라우저 라벨이 붙은 컨테이너를 꺼진 것까지 모두 돌려준다. */
    List<RuntimeContainer> list();
}
