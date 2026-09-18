package com.bifos.assistant.chat.application;

/**
 * 대화 한 turn 이 도는 동안 화면으로 보내는 사건이다.
 *
 * @param stepName 흐름의 단계 이름. {@code chief}, {@code researcher}, {@code engineer},
 *     {@code synthesizer} 넷이다. 단계 사건이 아니면 null
 * @param stepState {@code started}, {@code completed}, {@code failed} 셋이다. 단계 사건이 아니면 null
 */
public record ChatEvent(
        String type,
        String text,
        String toolName,
        String detail,
        Long conversationId,
        Long messageId,
        Long executionId,
        String code,
        String message,
        String stepName,
        String stepState) {

    public static ChatEvent delta(String text) {
        return new ChatEvent("delta", text, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent tool(String toolName, String status) {
        return new ChatEvent("tool", null, toolName, status, null, null, null, null, null, null, null);
    }

    /** 흐름의 한 단계가 시작되거나 끝났다. */
    public static ChatEvent step(String stepName, String state) {
        return new ChatEvent(
                "step", null, null, null, null, null, null, null, null, stepName, state);
    }

    public static ChatEvent done(Long conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
                "done", null, null, null, conversationId, messageId, executionId, null, null, null, null);
    }

    public static ChatEvent error(String code, String message) {
        return new ChatEvent("error", null, null, null, null, null, null, code, message, null, null);
    }
}
