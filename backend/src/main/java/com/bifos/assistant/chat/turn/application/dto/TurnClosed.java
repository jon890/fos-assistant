package com.bifos.assistant.chat.turn.application.dto;

/** turn 하나가 닫혔다. {@code stopped} 는 그 turn 이 중지로 끝났다는 뜻이다. */
public record TurnClosed(Long conversationId, boolean stopped) {
}
