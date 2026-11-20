package com.bifos.assistant.notification.application;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 웹 푸시 기기 목록을 바꾸는 일을 Control Plane 안에서 하나씩 돌린다(ADR-20261120 / web-push).
 *
 * <p>같은 주소가 다른 사용자 줄에 있는지 찾는 쿼리는 잠그지 않고 읽는다. 두 사용자가 같은 주소를 동시에 등록하면 서로를 보지 못해 둘 다에게
 * 기기가 남는다. 그래서 목록을 바꾸는 모든 일이 트랜잭션을 열기 전에 이 잠금을 잡고 커밋한 뒤 놓는다. 서버 한 대 전제(ADR-070)에 기댄다.
 */
@Component
public class PushRegistrationLock {

    private final ReentrantLock lock = new ReentrantLock();


    public <T> T run(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
