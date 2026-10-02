package com.bifos.assistant.chat.application;

/**
 * 위임 실행 하나가 어떻게든 끝났다. 끝난 상태는 실행 줄에 적혀 있다.
 *
 * <p>받는 쪽이 그 대화에 전할 결과가 있는지 실행 줄을 다시 읽어 정한다. 내는 쪽은 받는 쪽을 알지 않는다.
 *
 * @param conversationId 위임을 맡긴 부모 실행의 대화
 * @param executionId 끝난 위임 실행
 */
public record DelegationFinished(Long conversationId, Long executionId) {}
