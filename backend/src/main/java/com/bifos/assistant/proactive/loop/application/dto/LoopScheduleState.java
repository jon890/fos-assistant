package com.bifos.assistant.proactive.loop.application.dto;

import java.time.Instant;

/**
 * 매일 루프 설정의 값 둘이다. 값은 사용자와 에이전트마다 하나인 CHECK 예약 작업이 갖는다.
 *
 * @param enabled 그 작업이 켜져 있다. 작업이 없거나 꺼져 있으면 거짓이다
 * @param snoozedUntil 이 시각 전의 깨우기는 잇지 않는다. 비어 있으면 쉬지 않는다
 */
public record LoopScheduleState(boolean enabled, Instant snoozedUntil) {

    /** 지금 쉬는 중이다. 쉬기 시각이 지났으면 비운 것과 같다. */
    public boolean snoozedAt(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }
}
