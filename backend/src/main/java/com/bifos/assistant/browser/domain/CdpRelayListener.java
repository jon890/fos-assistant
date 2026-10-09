package com.bifos.assistant.browser.domain;

/** Chrome 쪽 중계 연결이 받은 것을 넘겨받는다. 한 연결의 조각은 차례대로 한 번에 하나씩 온다. */
public interface CdpRelayListener {

    /**
     * Chrome 이 보낸 글 메시지 조각 하나다. 이 함수가 돌아온 뒤에 다음 조각을 읽는다.
     *
     * @param last 메시지의 마지막 조각인가
     */
    void onFragment(String fragment, boolean last);

    /** 붙은 뒤에 상대가 닫았거나 연결이 실패했다. 한 번만 온다. {@link CdpRelay#close()} 로 닫았으면 오지 않는다. */
    void onClosed();
}
