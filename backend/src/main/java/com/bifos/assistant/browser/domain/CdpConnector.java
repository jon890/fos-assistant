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
     * <p>두 처리기는 WebSocket 을 읽는 스레드가 아니라 연결마다 하나인 스레드에서 차례대로 부른다. 처리기가 오래 걸려도 명령 응답은
     * 막히지 않으므로 처리기 안에서 {@link CdpConnection#send} 의 결과를 기다려도 된다. 다만 그동안 다음 사건은 기다린다.
     *
     * @param events CDP 가 보낸 사건을 받는다. 한 연결의 사건은 차례대로 한 번에 하나씩 온다
     * @param closed 붙은 뒤에 상대가 끊었거나 연결이 실패하면 한 번 부른다. 남은 사건을 모두 부른 뒤다. 붙지 못했거나
     *     {@link CdpConnection#close()} 로 닫았으면 부르지 않는다
     */
    CdpConnection connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed);
}
