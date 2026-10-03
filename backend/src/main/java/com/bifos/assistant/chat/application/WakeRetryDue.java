package com.bifos.assistant.chat.application;

/**
 * 사용자 실행 한도로 미룬 위임 결과 자동 turn 을 다시 시도할 때가 됐다(ADR-069).
 *
 * <p>{@link DelegationWakeService} 가 예약해 내고 {@link NextTurnDispatcher} 가 받는다. 대화를 다시 부르는 자리를
 * {@link NextTurnDispatcher} 하나로 두려고 직접 부르지 않고 사건으로 낸다.
 *
 * @param conversationId 다시 시도할 대화
 */
public record WakeRetryDue(Long conversationId) {}
