package com.bifos.assistant.usage.application;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 대화 turn 하나가 쥔 사용자 자리다(ADR-069).
 *
 * <p>{@link UserExecutionLimiter} 가 만들고, turn 잠금을 쥔 쪽이 들고 있다가 잠금을 풀 때 돌려준다. 여는 스레드와
 * 닫는 스레드가 다를 수 있고 닫기가 두 번 불릴 수 있어, 처음 한 번만 돌려준다.
 */
public final class TurnSlot {
    private final UserExecutionLimiter limiter;
    private final Long userId;
    private final AtomicBoolean released = new AtomicBoolean();

    TurnSlot(UserExecutionLimiter limiter, Long userId) {
        this.limiter = limiter;
        this.userId = userId;
    }

    /** 자리를 제한기에 돌려준다. 두 번째부터는 아무것도 하지 않는다. */
    public void release() {
        if (released.compareAndSet(false, true)) {
            limiter.releaseTurn(userId);
        }
    }
}
