package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrackingBackgroundTasksTest {

    private final TrackingBackgroundTasks tasks = new TrackingBackgroundTasks();

    @Test
    @DisplayName("띄운 스레드 둘과 그 안에서 다시 띄운 스레드가 모두 끝나야 awaitIdle 이 돌아온다")
    void awaitsStartedThreadsAndThreadsTheyStart() throws InterruptedException {
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        AtomicBoolean nested = new AtomicBoolean();
        CountDownLatch nestedMayFinish = new CountDownLatch(1);

        tasks.start("first", () -> first.set(true));
        tasks.start("second", () -> {
            tasks.start("nested", () -> {
                awaitQuietly(nestedMayFinish);
                nested.set(true);
            });
            second.set(true);
        });
        Thread releaser = Thread.ofVirtual().start(() -> {
            sleepQuietly(50);
            nestedMayFinish.countDown();
        });

        tasks.awaitIdle(Duration.ofSeconds(5));

        assertThat(first).as("first 스레드가 끝나야 한다").isTrue();
        assertThat(second).as("second 스레드가 끝나야 한다").isTrue();
        assertThat(nested).as("second 안에서 띄운 nested 스레드도 끝나야 한다").isTrue();
        releaser.join();
    }

    @Test
    @DisplayName("상한이 지나도 붙잡힌 스레드가 있으면 그 이름을 담은 AssertionError 를 던진다")
    void failsWithNameOfThreadStillRunningAfterLimit() throws InterruptedException {
        CountDownLatch hold = new CountDownLatch(1);
        tasks.start("finished-task", () -> {});
        Thread held = tasks.start("held-task", () -> awaitQuietly(hold));
        try {
            assertThatThrownBy(() -> tasks.awaitIdle(Duration.ofMillis(100)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("held-task")
                    .hasMessageNotContaining("finished-task");
        } finally {
            hold.countDown();
            held.join();
        }
    }

    @Test
    @DisplayName("만들기만 하고 시작하지 않은 스레드는 기다리지 않는다")
    void doesNotWaitForThreadThatWasNeverStarted() throws InterruptedException {
        tasks.unstarted("never-started", () -> {});

        tasks.awaitIdle(Duration.ofMillis(100));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
