package com.bifos.assistant.browser.domain;

import java.net.URI;
import java.util.function.Consumer;

/** 탭 하나에 CDP WebSocket 으로 붙는다. */
public interface CdpConnector {

    /**
     * 그 탭에 붙는다. 붙지 못하면 런타임 예외다.
     *
     * @param cdp {@link BrowserRuntime#cdpAddress(String)} 가 준 주소
     * @param targetId 탭의 대상 번호. 영문자와 숫자가 아니면 {@link IllegalArgumentException}
     * @param events CDP 가 보낸 사건을 받는다. 한 연결의 사건은 차례대로 한 번에 하나씩 온다
     * @param closed 상대가 연결을 끊었거나 연결이 실패했을 때 한 번 부른다
     */
    CdpConnection connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed);
}
