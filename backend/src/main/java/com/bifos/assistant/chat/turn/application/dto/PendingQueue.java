package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import java.util.List;

/** 한 대화의 대기 줄이다. {@code held} 가 참이면 사용자가 풀 때까지 보내지 않는다. */
public record PendingQueue(boolean held, List<PendingMessage> items) {

    public static PendingQueue of(PendingMessages queue) {
        return new PendingQueue(queue.held(), queue.items());
    }
}
