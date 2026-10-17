package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.ChatTurn;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 저장을 마친 turn의 취소 여부와 식별자로 화면에 보낼 종료 사건을 만든다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ChatTurnCompletion {
    static ChatEvent eventOf(ChatTurn turn) {
        return turn.cancelled()
            ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
            : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId());
    }
}
