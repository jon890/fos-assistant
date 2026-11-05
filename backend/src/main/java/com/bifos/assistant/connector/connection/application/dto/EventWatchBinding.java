package com.bifos.assistant.connector.connection.application.dto;

/**
 * 에이전트 상세가 보이는 바인딩 하나의 사건 감지 사실이다(ADR-20261105 / event-watch). 까닭 코드를 고르는 일은 부르는 쪽이 한다.
 *
 * @param usable 연결과 바인딩이 {@code READY} 이고 켜려는 의도가 있다
 * @param enabled 이 바인딩에 사건 감지를 켰다
 * @param elsewhere 같은 연결의 다른 바인딩이 사건 감지를 켰다
 * @param state 저장된 상태 JSON 글. 꺼져 있으면 null 이다
 */
public record EventWatchBinding(
    Long bindingId, String connectorId, boolean usable, boolean enabled, boolean elsewhere, String state) {
}
