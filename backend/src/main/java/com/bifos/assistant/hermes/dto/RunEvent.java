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
 */
public record RunEvent(
        String type,
        String text,
        String toolName,
        String detail,
        Long durationMs,
        Boolean failed) {
}
