package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    @DisplayName("연 핸들은 연 사용자와 대화를 갖고 중지한 뒤에야 중지 표시가 선다")
    void openedHandleKeepsOwnerAndConversationAndTurnsCancelledOnlyAfterCancel() {
        TurnHandle handle = turns.open(1L, 2L);

        assertThat(handle.userId()).isEqualTo(1L);
        assertThat(handle.getConversationId()).isEqualTo(2L);
        assertThat(handle.cancelled().get()).isFalse();

        assertThat(turns.cancel(handle)).isTrue();

        assertThat(handle.cancelled().get()).isTrue();
    }

    @Test
    @DisplayName("중지 뒤에 붙은 스트림도 유예 시간이 지나면 닫는다")
    void closesStreamAttachedAfterStopOnceGracePeriodPasses() throws InterruptedException {
        TurnHandle handle = turns.open(1L, 2L);
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
            TurnHandle handle = delayed.open(1L, 2L);
            CountDownLatch closed = new CountDownLatch(1);

            delayed.cancel(handle);
            delayed.attachStream(handle, closed::countDown);

            assertThat(closed.await(50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            delayed.shutdown();
        }
    }

    @Test
    @DisplayName("알림 줄을 저장하는 동안에는 도는 turn 으로 보이고 실행 번호가 없다")
    void showsRunningTurnWithoutExecutionIdWhileRunIfIdleWorks() {
        AtomicReference<TurnMark> markInside = new AtomicReference<>();
        AtomicReference<Throwable> openInside = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        turns.addCloseListener(event -> closed.incrementAndGet());

        boolean ran = turns.runIfIdle(2L, () -> {
            markInside.set(turns.markOf(2L));
            try {
                turns.open(1L, 2L);
            } catch (ApiException e) {
                openInside.set(e);
            }
        });

        assertThat(ran).isTrue();
        assertThat(markInside.get().running()).isTrue();
        assertThat(markInside.get().executionId()).isNull();
        assertThat(openInside.get()).isInstanceOf(ApiException.class);
        assertThat(((ApiException) openInside.get()).code()).isEqualTo(ErrorCode.CONVERSATION_BUSY);
        assertThat(turns.markOf(2L)).isEqualTo(TurnMark.NONE);
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("도는 turn 이 있으면 작업을 돌리지 않는다")
    void doesNotRunWorkWhileTurnIsOpen() {
        AtomicInteger worked = new AtomicInteger();
        turns.open(1L, 2L);

        assertThat(turns.runIfIdle(2L, worked::incrementAndGet)).isFalse();
        assertThat(worked.get()).isZero();
        assertThatThrownBy(() -> turns.open(1L, 2L)).isInstanceOf(ApiException.class);
    }
}
