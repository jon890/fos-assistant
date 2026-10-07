package com.bifos.assistant.shared.concurrent;

import org.springframework.stereotype.Component;

/**
 * 운영의 {@link BackgroundTasks} 구현이다(ADR-096).
 *
 * <p>직접 가상 스레드를 띄우던 때와 같은 동작이다. 스레드 이름과 스레드 수가 바뀌지 않는다.
 */
@Component
public class VirtualThreadBackgroundTasks implements BackgroundTasks {
    @Override
    public Thread start(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    @Override
    public Thread unstarted(String name, Runnable task) {
        return Thread.ofVirtual().name(name).unstarted(task);
    }
}
