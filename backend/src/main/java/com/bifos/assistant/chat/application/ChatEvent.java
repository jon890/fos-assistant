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

    /** 이 turn 의 실행 줄이 만들어졌다. 흐름이면 뿌리 실행의 번호다. */
    public static ChatEvent started(Long conversationId, Long executionId) {
        return new ChatEvent("started", null, null, null, conversationId, null, executionId, null, null, null, null);
    }

    public static ChatEvent tool(String toolName, String status) {
        return new ChatEvent("tool", null, toolName, status, null, null, null, null, null, null, null);
    }

    /** 흐름의 한 단계가 시작되거나 끝났다. */
    public static ChatEvent step(String stepName, String state) {
        return new ChatEvent(
                "step", null, null, null, null, null, null, null, null, stepName, state);
    }

    /**
     * 앞 provider 가 막혀 다음 모델로 넘어갔다.
     *
     * <p>{@code text} 에 넘어간 곳의 provider 와 모델을 적는다. 막힌 쪽의 오류 글은 싣지 않는다.
     */
    public static ChatEvent switched(String label) {
        return new ChatEvent("switched", label, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 지금까지 흘린 답 조각을 화면에서 지우라고 알린다.
     *
     * <p>실패한 시도의 조각이 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
     */
    public static ChatEvent reset() {
        return new ChatEvent("reset", null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatEvent done(Long conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
                "done", null, null, null, conversationId, messageId, executionId, null, null, null, null);
    }

    public static ChatEvent error(String code, String message) {
        return new ChatEvent("error", null, null, null, null, null, null, code, message, null, null);
    }
}
