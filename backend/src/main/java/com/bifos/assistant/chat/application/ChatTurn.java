package com.bifos.assistant.chat.application;

public record ChatTurn(Long conversationId, Long executionId, String assistantText) {
}
