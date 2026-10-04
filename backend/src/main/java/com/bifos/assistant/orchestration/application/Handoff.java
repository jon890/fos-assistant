package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AgentDelegationService} 의 요청 스레드와 실행 스레드가 주고받는 상태다.
 *
 * <p>「실행 줄이 생겼다」 와 「요청 스레드가 포기했다」 는 {@link #stage} 하나에서 compareAndSet 으로 정한다. 둘을
 * 따로 두면 요청 스레드가 줄이 없다고 본 직후 줄이 생겨 취소 확인을 지나 제출될 수 있다. 그러면 도구는 실패를 줬는데
 * 실행은 Hermes 에서 끝까지 돈다.
 */
final class Handoff {

    private enum Stage {
        /** 실행 줄을 아직 만들지 않았다 */
        WAITING,
        /** 실행 줄이 생겼다 */
        ROW_CREATED,
        /** 실행 줄을 만들기 전에 끝났다 */
        NO_ROW,
        /** 요청 스레드가 기다리다 포기했다. 뒤늦게 줄이 생기면 제출하지 않고 CANCELLED 로 끝난다 */
        ABANDONED
    }

    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.WAITING);
    /** 실행 줄이 생겼거나 줄 없이 끝났을 때 열린다. */
    private final CountDownLatch rowDecided = new CountDownLatch(1);
    /** 제출했거나 실행이 끝났을 때 열린다. */
    private final CountDownLatch settled = new CountDownLatch(1);

    private volatile AgentExecution execution;
    private volatile boolean submitted;
    private volatile RuntimeException failure;
    private volatile boolean checkEnded;

    /** 실행 스레드가 실행 줄을 만든 직후 부른다. 요청 스레드가 먼저 포기했으면 상태는 그대로 ABANDONED 다. */
    void onRowCreated(AgentExecution created) {
        execution = created;
        stage.compareAndSet(Stage.WAITING, Stage.ROW_CREATED);
        rowDecided.countDown();
    }

    void markSubmitted() {
        submitted = true;
        settled.countDown();
    }

    /** 실행 스레드가 끝날 때 부른다. 줄을 만들기 전에 끝났으면 그 예외를 남긴다. */
    void markEnded(RuntimeException cause) {
        failure = cause;
        stage.compareAndSet(Stage.WAITING, Stage.NO_ROW);
        rowDecided.countDown();
        settled.countDown();
    }

    /** 실행 스레드가 실행 줄을 만들기 전에 그 먼저 살펴보기가 끝난 것을 봤다. 곧이어 {@link #markEnded} 가 불린다. */
    void markCheckEnded() {
        checkEnded = true;
    }

    /** 요청 스레드가 포기한다. 실행 줄이 이미 생겼으면 거짓이고 그 줄을 돌려줘야 한다. */
    boolean abandon() {
        return stage.compareAndSet(Stage.WAITING, Stage.ABANDONED);
    }

    boolean abandoned() {
        return stage.get() == Stage.ABANDONED;
    }

    boolean rowCreated() {
        return stage.get() == Stage.ROW_CREATED;
    }

    boolean submitted() {
        return submitted;
    }

    AgentExecution execution() {
        return execution;
    }

    boolean checkEnded() {
        return checkEnded;
    }

    RuntimeException failure() {
        return failure;
    }

    boolean awaitRowDecided(long deadline) {
        return await(rowDecided, deadline);
    }

    boolean awaitSettled(long deadline) {
        return await(settled, deadline);
    }

    /** 끊기면 제한 시간이 지난 것과 같게 본다. 끊긴 표시는 되살린다. */
    private static boolean await(CountDownLatch latch, long deadline) {
        try {
            return latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
