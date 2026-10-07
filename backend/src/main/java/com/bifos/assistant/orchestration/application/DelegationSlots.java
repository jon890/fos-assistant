package com.bifos.assistant.orchestration.application;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버 전체에서 동시에 도는 위임의 자리를 센다. 한도는 얻을 때마다 받아 견준다(ADR-20261007 / live-properties).
 *
 * <p>먼저 늘리고 넘으면 되돌리는 방식은 거절될 요청이 잠깐 올린 수 때문에 한도 안의 다른 요청까지 거절할 수 있다. 그래서 한도 아래일 때만
 * compare-and-set 으로 하나 늘리고, 한도를 넘는 값은 한 번도 쓰지 않는다.
 */
final class DelegationSlots {
    private final AtomicInteger active = new AtomicInteger();

    /** 자리 하나를 얻는다. 이미 {@code max} 개를 쓰고 있으면 얻지 못한다. */
    boolean tryAcquire(int max) {
        while (true) {
            int current = active.get();
            if (current >= max) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** {@link #tryAcquire} 로 얻은 자리를 돌려준다. */
    void release() {
        active.decrementAndGet();
    }
}
