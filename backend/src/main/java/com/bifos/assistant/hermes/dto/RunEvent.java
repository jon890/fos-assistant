package com.bifos.assistant.hermes.dto;

/**
 * Hermes 가 실행 스트림으로 보내는 사건 하나다.
 *
 * @param type Hermes 가 {@code event} 로 보내는 사건 이름
 * @param text 답 조각
 * @param toolName 도구 이름이나 하위 에이전트 이름
 * @param detail 화면에 한 줄로 보일 설명
 * @param durationMs 걸린 시간. Hermes 는 초 단위 실수로 보내고 여기에는 밀리초 정수로 담는다
 * @param failed 그 사건이 실패로 끝났는지. Hermes 가 알려주지 않으면 {@code null} 이다
 * @param subagentId 하위 에이전트 사건을 짝짓는 번호
 * @param goal 하위 에이전트의 목표
 * @param model 하위 에이전트의 모델
 * @param childSessionId 하위 에이전트의 session 번호
 * @param inputTokens 하위 에이전트의 입력 토큰
 * @param outputTokens 하위 에이전트의 출력 토큰
 * @param status 하위 에이전트의 종료 상태
 */
public record RunEvent(
        String type,
        String text,
        String toolName,
        String detail,
        Long durationMs,
        Boolean failed,
        String subagentId,
        String goal,
        String model,
        String childSessionId,
        Long inputTokens,
        Long outputTokens,
        String status) {

    public RunEvent(String type, String text, String toolName, String detail, Long durationMs, Boolean failed) {
        this(type, text, toolName, detail, durationMs, failed, null, null, null, null, null, null, null);
    }
}
