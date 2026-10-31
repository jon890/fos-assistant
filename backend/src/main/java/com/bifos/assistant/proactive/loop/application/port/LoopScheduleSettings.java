package com.bifos.assistant.proactive.loop.application.port;

import com.bifos.assistant.proactive.loop.application.dto.LoopScheduleState;
import java.time.Instant;
import java.util.Optional;

/**
 * 매일 루프의 켜기와 쉬기를 사용자와 에이전트마다 하나인 CHECK 예약 작업이 갖게 하는 경계다(ADR-20261101 / proactive-suggestion).
 * 공유 에이전트도 사용자마다 작업이 따로라 사용자별 설정이 서로 바뀌지 않는다.
 */
public interface LoopScheduleSettings {

    /** 그 사용자와 에이전트의 설정이다. 작업이 없으면 빈 값이다. */
    Optional<LoopScheduleState> find(Long userId, Long agentId);


    /**
     * 그 사용자의 CHECK 작업 줄을 모두 쓰기 잠금으로 잡고 그 에이전트의 설정을 읽는다. 같은 사용자의 두 깨우기가 하루 상한을 함께
     * 넘지 않게 부르는 쪽의 트랜잭션 안에서 부른다.
     */
    Optional<LoopScheduleState> findLockingUser(Long userId, Long agentId);


    /**
     * 설정을 저장한다. 작업이 없으면 만들고 있으면 바꾼다. 켜면 작업을 켜고 끄면 멈춘다. 부르는 쪽의 트랜잭션 안에서 부른다.
     *
     * @param snoozedUntil 비어 있으면 쉬지 않는다
     */
    LoopScheduleState save(Long userId, Long agentId, boolean enabled, Instant snoozedUntil, Instant now);
}
