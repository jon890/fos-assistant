package com.bifos.assistant.shared.concurrent;

/**
 * 요청 밖에서 도는 작업을 이름 붙인 가상 스레드로 띄운다(ADR-20261007 / background-tasks).
 *
 * <p>운영 코드는 가상 스레드를 직접 만들지 않고 이 빈으로 띄운다.
 * 검사는 이 빈을 바꿔 끼워 띄운 스레드를 모두 쥐고, 검사가 끝날 때 join 한다.
 */
public interface BackgroundTasks {
    /** 이름 붙인 가상 스레드에서 작업을 바로 시작한다. */
    Thread start(String name, Runnable task);

    /** 이름 붙인 가상 스레드를 만들기만 한다. 부르는 쪽이 시작한다. */
    Thread unstarted(String name, Runnable task);
}
