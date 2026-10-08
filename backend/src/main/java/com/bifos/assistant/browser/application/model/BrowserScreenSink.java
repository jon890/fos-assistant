package com.bifos.assistant.browser.application.model;

/** 화면 사건을 받는 쪽이다. 웹 경로에서는 SSE 하나다. */
public interface BrowserScreenSink {

    /** 사건 하나를 보낸다. 다른 쓰기가 끝나기를 기다린다. 받는 쪽이 이미 끊겼거나 쓰지 못하면 거짓이다. */
    boolean send(String event, Object data);

    /** 다른 쓰기가 진행 중이면 기다리지 않고 거짓이다. 끝낼 때 막힌 받는 쪽에 붙잡히지 않으려고 쓴다. */
    boolean trySend(String event, Object data);

    /** 끊김을 알아보려고 사건이 아닌 주석 줄을 보낸다. 다른 쓰기가 진행 중이면 건너뛴다. 받는 쪽이 끊겼으면 거짓이다. */
    boolean ping();

    /** 받는 쪽을 끝낸다. 두 번 불러도 되고, 다른 쓰기가 막혀 있어도 기다리지 않는다. */
    void complete();

    /** 받는 쪽이 먼저 끊기면 부를 일을 건다. 이미 끊겼으면 바로 부른다. */
    void onClose(Runnable callback);
}
