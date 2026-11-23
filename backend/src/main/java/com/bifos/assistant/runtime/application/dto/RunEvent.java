package com.bifos.assistant.runtime.application.dto;

/**
 * 런타임이 실행 스트림으로 보내는 사건 하나다.
 *
 * @param kind 사건의 종류
 * @param text 답 조각
 * @param toolName 도구 이름이나 하위 에이전트 이름
 * @param detail 도구 사건이면 비밀값과 UUID를 가린 설명. 가릴 범위에 든 도구는 내용 전체를 가린다
 * @param durationMs 걸린 시간(밀리초)
 * @param failed 그 사건이 실패로 끝났는지. 런타임이 알려주지 않으면 {@code null} 이다
 * @param subagentId 하위 에이전트 사건을 짝짓는 번호
 * @param goal 하위 에이전트의 목표
 * @param model 하위 에이전트의 모델
 * @param childSessionId 하위 에이전트의 session 번호
 * @param inputTokens 하위 에이전트의 입력 토큰
 * @param outputTokens 하위 에이전트의 출력 토큰
 * @param status 하위 에이전트의 종료 상태
 * @param skillName 스킬을 읽는 도구의 시작 사건에서 모델이 읽은 스킬 이름. 스킬 사용 기록만 쓰고 화면과 사건 저장에는 넣지
 * 않는다. 그 밖의 사건에서는 {@code null} 이다
 * @param alreadyStreamed 중간 안내의 일부가 답 조각으로 전달됐는지. 모르면 null
 * @param streamedPrefixLength 안내로 확인한 직전 답 조각 구간의 UTF-16 길이
 * @param interimId 실행 안에서 같은 안내의 재수신을 구분할 불투명한 번호
 */
public record RunEvent(
    RunEventKind kind,
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
    String status,
    String skillName,
    Boolean alreadyStreamed,
    Integer streamedPrefixLength,
    String interimId) {
}
