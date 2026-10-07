package com.bifos.assistant.browser.application.model;

/** 화면이나 중계가 브라우저를 쓰는 동안 쥐는 핸들이다. 닫으면 그 사용이 끝난다. 두 번 닫아도 한 번만 센다. */
public interface BrowserUsageHandle extends AutoCloseable {

    @Override
    void close();
}
