package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.hermes.HermesRunsClient;
import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnCancellationTest {

    private final TurnCancellation turns = new TurnCancellation(mock(HermesRunsClient.class), Duration.ofMillis(10));

    @AfterEach
    void closeScheduler() {
        turns.shutdown();
    }

    @Test
    @DisplayName("중지 뒤에 붙은 스트림도 유예 시간이 지나면 닫는다")
    void closesStreamAttachedAfterStopOnceGracePeriodPasses() throws InterruptedException {
        TurnCancellation.TurnHandle handle = turns.open(1L, 2L);
        CountDownLatch closed = new CountDownLatch(1);
        Closeable stream = closed::countDown;

        turns.cancel(handle);
        turns.confirmStop(handle);
        turns.awaitStreamOrGrace(handle, new CompletableFuture<>());
        turns.attachStream(handle, stream);

        assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("Hermes 중지가 확정되기 전에는 유예 시간으로 스트림을 닫지 않는다")
    void doesNotCloseStreamByGracePeriodBeforeHermesStopIsConfirmed() throws InterruptedException {
        TurnCancellation delayed = new TurnCancellation(mock(HermesRunsClient.class), Duration.ofMillis(10));
        try {
            TurnCancellation.TurnHandle handle = delayed.open(1L, 2L);
            CountDownLatch closed = new CountDownLatch(1);

            delayed.cancel(handle);
            delayed.attachStream(handle, closed::countDown);

            assertThat(closed.await(50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            delayed.shutdown();
        }
    }
}
