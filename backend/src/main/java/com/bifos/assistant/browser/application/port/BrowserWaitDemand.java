package com.bifos.assistant.browser.application.port;

import java.time.Instant;

/**
 * 그 사용자가 사람을 기다리는 일이 있어 브라우저를 켜 두어야 하는지 답한다. 자동 중지가 끄기 전에 묻는다.
 *
 * <p>기다림은 그 브라우저가 이번에 켜진 뒤 생긴 열린 화면 넘기기와, 「내 브라우저」 를 쓰는 플러그인의 기다리는 승인이다. 뜻은
 * {@code docs/features/browser-cowork/spec.md} 의 REQ1 이 갖는다. 승인과 넘기기는 {@code connector} 가 갖고 {@code browser} 가
 * 그 패키지보다 아래라 이 인터페이스를 두고 {@code connector} 가 구현한다.
 */
public interface BrowserWaitDemand {

    /**
     * 기다리는 일이 있는가.
     *
     * @param userId 브라우저 주인
     * @param since 그 브라우저가 이번에 켜진 시각. 그 전에 생긴 넘기기의 탭은 이미 없으므로 세지 않는다
     */
    boolean waiting(long userId, Instant since);
}
