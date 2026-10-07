package com.bifos.assistant.browser.domain;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;

/** 탭 하나에 붙은 CDP 연결이다. */
public interface CdpConnection extends AutoCloseable {

    /**
     * 명령을 보낸다. 응답의 {@code result} 로 끝나고, CDP 가 오류로 답하거나 정한 시간 안에 답하지 않거나 연결이 끊기면 실패로 끝난다.
     * 실패 메시지에 인자를 싣지 않는다.
     */
    CompletableFuture<JsonNode> send(String method, Map<String, Object> params);

    /** 연결을 닫는다. 기다리던 명령은 실패로 끝난다. 두 번 닫아도 된다. 이렇게 닫으면 닫힘 알림을 부르지 않는다. */
    @Override
    void close();
}
