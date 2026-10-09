package com.bifos.assistant.browser.domain;

import java.net.URI;

/** 브라우저 중계가 Chrome 의 {@code /devtools/<종류>/<번호>} WebSocket 에 붙는다. */
public interface CdpRelayConnector {

    /**
     * 그 대상에 붙는다. 붙을 때까지 기다리고, 붙지 못하면 런타임 예외다.
     *
     * @param cdp {@link BrowserRuntime#cdpAddress(String)} 가 준 주소
     * @param kind {@code page} 나 {@code browser}. 아니면 {@link IllegalArgumentException}
     * @param id 대상 번호. 영문자와 숫자, {@code -} 로 128자까지가 아니면 {@link IllegalArgumentException}
     * @param listener Chrome 이 보낸 조각과 닫힘을 받는다
     */
    CdpRelay open(URI cdp, String kind, String id, CdpRelayListener listener);
}
