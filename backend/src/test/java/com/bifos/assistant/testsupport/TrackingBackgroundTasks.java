package com.bifos.assistant.testsupport;

import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 띄운 스레드를 모두 쥐는 검사용 {@link BackgroundTasks} 다(ADR-20261007 / background-tasks).
 *
 * <p>운영 구현과 같은 이름의 가상 스레드를 띄운다. {@link IntegrationTestIsolation} 이 검사가 끝날 때 {@link #awaitIdle} 로
 * 그 스레드가 모두 끝나기를 기다린다. 기다리는 것은 시간이 아니라 스레드의 끝이다.
 */
public final class TrackingBackgroundTasks implements BackgroundTasks {
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

    @Override
    public Thread start(String name, Runnable task) {
        Thread thread = unstarted(name, task);
        thread.start();
        return thread;
    }

    @Override
    public Thread unstarted(String name, Runnable task) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(task);
        threads.add(thread);
        return thread;
    }

    /**
     * 쥔 스레드가 모두 끝날 때까지 차례로 join 한다.
     *
     * <p>join 하는 동안 새로 띄운 스레드도 다시 돌며 기다린다. 만들기만 하고 아직 시작하지 않은 스레드는 기다리지 않되 목록에 남긴다.
     * 나중에 시작되면 다음 호출이 기다린다. 끝난 스레드만 목록에서 뺀다.
     *
     * <p>상한 안에 끝나지 않은 스레드가 있으면 그 이름을 담아 실패한다. 그 스레드는 정리할 수 없으므로 실패하기 전에 목록에서 뺀다. 남겨
     * 두면 같은 컨텍스트를 쓰는 뒤 검사가 모두 같은 스레드를 상한까지 기다리다 실패해, 원인이 어느 검사인지 흐려진다.
     *
     * @throws AssertionError 상한이 지나도 살아 있는 스레드가 있을 때
     */
    public void awaitIdle(Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            threads.removeIf(thread -> thread.getState() == Thread.State.TERMINATED);
            List<Thread> alive = threads.stream().filter(Thread::isAlive).toList();
            if (alive.isEmpty()) {
                return;
            }
            for (Thread thread : alive) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !thread.join(Duration.ofNanos(left))) {
                    List<Thread> stuck =
                            threads.stream().filter(Thread::isAlive).toList();
                    threads.removeAll(stuck);
                    throw new AssertionError("상한 " + limit + " 이 지나도 이 검사가 남긴 백그라운드 작업이 끝나지 않았다. "
                            + "정리할 수 없어 추적에서 뺀다. threads="
                            + stuck.stream().map(Thread::getName).sorted().toList());
                }
                threads.remove(thread);
            }
        }
    }
}
