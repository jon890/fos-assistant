package com.bifos.assistant.chat.application;

import java.util.Collection;
import java.util.Set;

/**
 * 실행 가운데 예약 작업의 발화가 연 것을 가려 준다.
 *
 * <p>{@code chat} 은 작업을 모른다(ADR-068). 구현은 작업 쪽이 갖는다. 예약 작업 turn 은 지시가 {@code USER} 메시지로 남지만
 * 사람이 기다리는 turn 이 아니라서 첫 반응 시간 집계에서 뺀다.
 */
public interface ScheduledTurnExecutions {

    /**
     * 예약 작업 발화의 루트 실행인 번호만 돌려준다. 번호가 비면 빈 집합이다.
     *
     * @param executionIds 가릴 루트 실행 번호들
     */
    Set<Long> scheduledAmong(Collection<Long> executionIds);
}
