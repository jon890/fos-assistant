package com.bifos.assistant.chat.application;

public record ChatEvent(
        String type,
        String text,
        String toolName,
        String detail,
        Long conversationId,
        Long messageId,
        Long executionId,
        String code,
        String message) {

    public static ChatEvent delta(String text) {
        return new ChatEvent("delta", text, null, null, null, null, null, null, null);
    }

    public static ChatEvent tool(String toolName, String status) {
        return new ChatEvent("tool", null, toolName, status, null, null, null, null, null);
    }

    public static ChatEvent done(Long conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
                "done", null, null, null, conversationId, messageId, executionId, null, null);
    }

    public static ChatEvent error(String code, String message) {
        return new ChatEvent("error", null, null, null, null, null, null, code, message);
    }
}
