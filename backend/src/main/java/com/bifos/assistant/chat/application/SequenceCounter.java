package com.bifos.assistant.chat.application;

/** 실행 하나 안에서 사건 순서를 1부터 센다. 스트림을 읽는 스레드가 하나라 잠금이 필요 없다. */
final class SequenceCounter {
    private int next = 1;

    int peek() {
        return next;
    }

    void advance() {
        next++;
    }
}
