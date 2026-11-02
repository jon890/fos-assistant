package com.bifos.assistant.chat.conversation.domain;

/**
 * 저장된 대기 줄({@code conversation.pending_messages})을 읽지 못했다(ADR-048). 모르는 버전이거나 형식이 깨졌다.
 *
 * <p>부르는 쪽이 이 실패만 골라 그 대화를 건너뛸 수 있게 따로 둔다. 다른 실패까지 같은 이름으로 삼키지 않는다.
 */
public class UnreadablePendingMessagesException extends IllegalStateException {

    public UnreadablePendingMessagesException(String message) {
        super(message);
    }


    public UnreadablePendingMessagesException(String message, Throwable cause) {
        super(message, cause);
    }
}
