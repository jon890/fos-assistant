package com.bifos.assistant.chat.application;

/**
 * 읽은 대기 행이 사용자 메시지로 저장하기 전에 취소됐다.
 *
 * <p>{@link ChatService} 안에서 저장 트랜잭션을 되돌리고 대기 줄을 다시 읽게 하는 신호다. 밖으로 나가지 않는다.
 */
class PendingQueueChangedException extends RuntimeException {

    PendingQueueChangedException() {
        super("a queued message was cancelled before it was sent");
    }
}
