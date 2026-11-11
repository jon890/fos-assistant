package com.bifos.assistant.chat.nextturn.application.dto;

/**
 * 위임 실행 하나의 실행 줄이 생겼다. {@link DelegationFinished} 와 짝이다.
 *
 * <p>받는 쪽이 실행 줄을 다시 읽어 무엇을 할지 정한다. 내는 쪽은 받는 쪽을 알지 않는다.
 *
 * @param conversationId 위임을 맡긴 부모 실행의 대화
 * @param executionId 시작한 위임 실행
 */
public record DelegationStarted(Long conversationId, Long executionId) {
}
