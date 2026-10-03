package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.TurnSlot;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.application.UserExecutionProperties;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnCancellationTest {

    private final TurnCancellation turns = turnsWithLimit(1000);

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
        TurnCancellation delayed = turnsWithLimit(1000);
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

    @Test
    @DisplayName("한도가 1 이면 다른 대화를 열 때 USER_BUSY 이고 그 대화의 잠금이 남지 않는다")
    void rejectsSecondConversationWithUserBusyAndLeavesNoLock() {
        UserExecutionLimiter limiter = limiter(1);
        TurnCancellation limited = turnsWith(limiter);
        try {
            AtomicInteger closed = new AtomicInteger();
            limited.addCloseListener(event -> closed.incrementAndGet());
            limited.open(1L, 2L);

            assertThatThrownBy(() -> limited.open(1L, 3L))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USER_BUSY));

            assertThat(limited.markOf(3L)).isEqualTo(TurnMark.NONE);
            assertThat(closed.get()).as("열지 못한 대화의 닫기 리스너").isZero();
            assertThat(limiter.used(1L)).isEqualTo(1);
        } finally {
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("같은 대화를 두 스레드가 함께 열면 하나만 열리고 자리는 하나만 쓰인다")
    void opensSameConversationOnceUnderRace() throws Exception {
        UserExecutionLimiter limiter = limiter(1);
        TurnCancellation limited = turnsWith(limiter);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (long conversationId = 100L; conversationId < 150L; conversationId++) {
                long target = conversationId;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<TurnHandle>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        try {
                            return limited.open(1L, target);
                        } catch (ApiException e) {
                            // 한 자리만 남았을 때 진 쪽은 자리를 잠깐 다투어 USER_BUSY 를 받을 수 있다. 한도를 넘지 않는 쪽의 오차다.
                            assertThat(e.code()).isIn(ErrorCode.CONVERSATION_BUSY, ErrorCode.USER_BUSY);
                            return null;
                        }
                    }));
                }
                start.countDown();
                List<TurnHandle> opened = new ArrayList<>();
                for (Future<TurnHandle> result : results) {
                    TurnHandle handle = result.get(5, TimeUnit.SECONDS);
                    if (handle != null) {
                        opened.add(handle);
                    }
                }

                assertThat(opened).as("대화 %d 에 열린 turn", target).hasSize(1);
                assertThat(limiter.used(1L)).as("대화 %d 를 연 뒤 쓴 자리", target).isEqualTo(1);

                limited.close(opened.get(0));
                assertThat(limiter.used(1L)).as("대화 %d 를 닫은 뒤 쓴 자리", target).isZero();
            }
        } finally {
            pool.shutdownNow();
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("같은 대화에 이미 turn 이 있으면 사용자 자리가 없어도 CONVERSATION_BUSY 가 먼저 나간다")
    void reportsConversationBusyBeforeUserBusy() {
        UserExecutionLimiter limiter = limiter(1);
        TurnCancellation limited = turnsWith(limiter);
        try {
            limited.open(1L, 2L);

            assertThatThrownBy(() -> limited.open(1L, 2L))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONVERSATION_BUSY));
            assertThat(limiter.used(1L)).isEqualTo(1);
        } finally {
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("자리를 얻는 사이 같은 대화를 다른 요청이 열어 자리가 없으면 USER_BUSY 가 아니라 CONVERSATION_BUSY 다")
    void reportsConversationBusyWhenSameConversationOpensWhileAcquiringSlot() {
        AtomicReference<TurnCancellation> turnsRef = new AtomicReference<>();
        AtomicBoolean rivalStarted = new AtomicBoolean();
        AtomicReference<TurnHandle> rival = new AtomicReference<>();
        // 대화 잠금을 보고 지나간 뒤 자리를 얻기 전에, 같은 대화를 연 다른 요청이 하나뿐인 자리와 잠금을 먼저 가져간다.
        UserExecutionLimiter limiter =
                new UserExecutionLimiter(
                        new UserExecutionProperties(1, 0, null),
                        mock(AgentExecutionRepository.class),
                        mock(HermesRunsClient.class),
                        new HermesProperties(null, null, null, null, null, null, null, null)) {
                    @Override
                    public TurnSlot acquireTurn(Long userId) {
                        if (rivalStarted.compareAndSet(false, true)) {
                            rival.set(turnsRef.get().open(userId, 2L));
                        }
                        return super.acquireTurn(userId);
                    }
                };
        TurnCancellation limited = turnsWith(limiter);
        turnsRef.set(limited);
        try {
            assertThatThrownBy(() -> limited.open(1L, 2L))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONVERSATION_BUSY));

            assertThat(rival.get()).as("먼저 연 다른 요청의 turn").isNotNull();
            assertThat(limiter.used(1L)).as("진 쪽이 남긴 자리 없이 쓴 자리").isEqualTo(1);
            limited.close(rival.get());
            assertThat(limiter.used(1L)).as("먼저 연 turn 을 닫은 뒤 쓴 자리").isZero();
            assertThat(limited.markOf(2L)).isEqualTo(TurnMark.NONE);
        } finally {
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("닫으면 자리가 돌아와 다른 대화를 열고, 두 번 닫아도 한 번만 돌아온다")
    void returnsSlotOnceOnClose() {
        UserExecutionLimiter limiter = limiter(2);
        TurnCancellation limited = turnsWith(limiter);
        try {
            TurnHandle first = limited.open(1L, 2L);
            limited.open(1L, 3L);

            limited.close(first);
            limited.close(first);

            assertThat(limiter.used(1L)).isEqualTo(1);
            limited.open(1L, 4L);
            assertThat(limiter.used(1L)).isEqualTo(2);
            assertThatThrownBy(() -> limited.open(1L, 5L))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USER_BUSY));
        } finally {
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("닫기 리스너가 불릴 때 자리는 이미 돌아와 있다")
    void returnsSlotBeforeCloseListenerRuns() {
        UserExecutionLimiter limiter = limiter(1);
        TurnCancellation limited = turnsWith(limiter);
        try {
            AtomicReference<TurnHandle> reopened = new AtomicReference<>();
            limited.addCloseListener(event -> {
                if (event.conversationId().equals(2L)) {
                    reopened.set(limited.open(1L, 3L));
                }
            });

            limited.close(limited.open(1L, 2L));

            assertThat(reopened.get()).as("닫기 리스너가 연 다음 turn").isNotNull();
        } finally {
            limited.shutdown();
        }
    }

    @Test
    @DisplayName("기동 정리가 여는 turn 은 한도를 넘겨도 열린다")
    void opensRecoveredTurnBeyondLimit() {
        UserExecutionLimiter limiter = limiter(1);
        TurnCancellation limited = turnsWith(limiter);
        try {
            limited.open(1L, 2L);

            TurnHandle recovered = limited.openRecovered(1L, 3L);

            assertThat(limited.markOf(3L).running()).isTrue();
            assertThat(limiter.used(1L)).isEqualTo(2);
            limited.close(recovered);
            assertThat(limiter.used(1L)).isEqualTo(1);
        } finally {
            limited.shutdown();
        }
    }

    private static UserExecutionLimiter limiter(int maxRunning) {
        return new UserExecutionLimiter(
                new UserExecutionProperties(maxRunning, 0, null),
                mock(AgentExecutionRepository.class),
                mock(HermesRunsClient.class),
                new HermesProperties(null, null, null, null, null, null, null, null));
    }

    private static TurnCancellation turnsWith(UserExecutionLimiter limiter) {
        return new TurnCancellation(mock(HermesRunsClient.class), Duration.ofMillis(10), limiter);
    }

    private static TurnCancellation turnsWithLimit(int maxRunning) {
        return turnsWith(limiter(maxRunning));
    }
}
