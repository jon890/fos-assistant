package com.bifos.assistant.chat.application;

/** 저장을 마친 turn의 취소 여부와 식별자로 화면에 보낼 종료 사건을 만든다. */
final class ChatTurnCompletion {
    private ChatTurnCompletion() {}

    static ChatEvent eventOf(ChatTurn turn) {
        return turn.cancelled()
                ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId());
    }
}
