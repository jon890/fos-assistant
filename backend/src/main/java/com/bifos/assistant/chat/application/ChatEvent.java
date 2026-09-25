package com.bifos.assistant.chat.application;

/**
 * 대화 한 turn 이 도는 동안 화면으로 보내는 사건이다.
 *
 * @param stepName 흐름의 단계 이름. 단계 사건이 아니면 null
 * @param stepState {@code started}, {@code completed}, {@code failed} 셋이다. 단계 사건이 아니면 null
 * @param phase 도구나 하위 에이전트 사건의 시작 또는 완료 단계
 * @param durationMs 완료 사건의 걸린 시간
 * @param failed 완료 사건의 실패 여부
 * @param subagentId 하위 에이전트 사건의 짝을 맞추는 번호
 * @param goal 하위 에이전트의 목표. 없으면 Hermes 의 설명
 * @param model 하위 에이전트의 모델
 * @param inputTokens 하위 에이전트의 입력 토큰
 * @param outputTokens 하위 에이전트의 출력 토큰
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
        String stepState,
        String phase,
        Long durationMs,
        Boolean failed,
        String subagentId,
        String goal,
        String model,
        Long inputTokens,
        Long outputTokens) {

    public static final String STARTED = "started";
    public static final String COMPLETED = "completed";

    public static ChatEvent delta(String text) {
        return new ChatEvent("delta", text, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** 이 turn 의 실행 줄이 만들어졌다. 흐름이면 뿌리 실행의 번호다. */
    public static ChatEvent started(Long conversationId, Long executionId) {
        return new ChatEvent("started", null, null, null, conversationId, null, executionId, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static ChatEvent tool(String toolName, String detail, String phase, Long durationMs, Boolean failed) {
        return new ChatEvent("tool", null, toolName, detail, null, null, null, null, null, null, null,
                phase, durationMs, failed, null, null, null, null, null);
    }

    public static ChatEvent subagent(String subagentId, String goal, String model, String phase,
            Long inputTokens, Long outputTokens, Long durationMs, Boolean failed) {
        return new ChatEvent("subagent", null, null, null, null, null, null, null, null, null, null,
                phase, durationMs, failed, subagentId, goal, model, inputTokens, outputTokens);
    }

    /** 흐름의 한 단계가 시작되거나 끝났다. */
    public static ChatEvent step(String stepName, String state) {
        return new ChatEvent(
                "step", null, null, null, null, null, null, null, null, stepName, state,
                null, null, null, null, null, null, null, null);
    }

    /**
     * 앞 provider 가 막혀 다음 모델로 넘어갔다.
     *
     * <p>{@code text} 에 넘어간 곳의 provider 와 모델을 적는다. 막힌 쪽의 오류 글은 싣지 않는다.
     */
    public static ChatEvent switched(String label) {
        return new ChatEvent("switched", label, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * 지금까지 흘린 답 조각을 화면에서 지우라고 알린다.
     *
     * <p>실패한 시도의 조각이 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
     */
    public static ChatEvent reset() {
        return new ChatEvent("reset", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static ChatEvent done(Long conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
                "done", null, null, null, conversationId, messageId, executionId, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static ChatEvent stopped(Long conversationId, Long messageId, Long executionId) {
        return new ChatEvent(
                "stopped", null, null, null, conversationId, messageId, executionId, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static ChatEvent error(String code, String message) {
        return new ChatEvent("error", null, null, null, null, null, null, code, message, null, null,
                null, null, null, null, null, null, null, null);
    }
}
