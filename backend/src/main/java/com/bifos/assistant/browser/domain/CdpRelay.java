package com.bifos.assistant.browser.domain;

/**
 * 브라우저 중계가 Chrome 쪽에 연 WebSocket 하나다. 받은 글 메시지 조각을 모으지 않고 그대로 보낸다.
 *
 * <p>Chrome 이 보낸 조각과 닫힘은 열 때 넘긴 {@link CdpRelayListener} 가 받는다.
 */
public interface CdpRelay {

    /**
     * 글 메시지 조각 하나를 보낸다. 앞 조각의 보내기가 끝날 때까지 기다린 뒤 보내고, 이 조각의 보내기가 끝나야 돌아온다.
     *
     * @param fragment 조각. 보내는 동안 바꾸지 않는다
     * @param last 메시지의 마지막 조각인가
     * @throws IllegalStateException 이미 닫혔거나 보내지 못했을 때. 메시지에 조각을 싣지 않는다
     */
    void send(String fragment, boolean last);

    /** 닫는다. 두 번 불러도 된다. 이렇게 닫으면 {@link CdpRelayListener#onClosed()} 를 부르지 않는다. */
    void close();
}
