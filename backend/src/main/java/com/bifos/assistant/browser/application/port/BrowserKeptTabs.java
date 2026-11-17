package com.bifos.assistant.browser.application.port;

import java.time.Instant;

/**
 * 중계가 닫지 않을 탭인지 답한다. 사람이 풀고 있거나 이어 가기를 기다리는 탭이다(docs/features/browser-cowork/spec.md 의 REQ9,
 * REQ11). 넘기기와 사람 기다림은 {@code connector} 가 갖고 {@code browser} 가 그보다 아래라 이 인터페이스를 두고 {@code connector}
 * 가 구현한다.
 */
public interface BrowserKeptTabs {

    /**
     * 그 탭을 가리키는 열린 넘기기나 살아 있는 사람 기다림이 있는가.
     *
     * @param userId 브라우저 주인
     * @param tab CDP target id
     * @param since 그 브라우저가 이번에 켜진 시각. 그 전에 생긴 넘기기의 탭은 이미 없으므로 세지 않는다
     */
    boolean kept(long userId, String tab, Instant since);
}
