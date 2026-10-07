package com.bifos.assistant.browser.application.model;

/** 화면 사건을 받는 쪽이다. 웹 경로에서는 SSE 하나다. */
public interface BrowserScreenSink {

    /** 사건 하나를 보낸다. 받는 쪽이 이미 끊겼거나 쓰지 못하면 거짓이다. */
    boolean send(String event, Object data);

    /** 받는 쪽을 끝낸다. 두 번 불러도 된다. */
    void complete();

    /** 받는 쪽이 먼저 끊기면 부를 일을 건다. 이미 끊겼으면 바로 부른다. */
    void onClose(Runnable callback);
}
