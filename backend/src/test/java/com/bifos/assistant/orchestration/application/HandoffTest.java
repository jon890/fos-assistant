package com.bifos.assistant.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 요청 스레드의 포기와 실행 스레드의 줄 생성 가운데 하나만 이기는 것을 고정한다. */
class HandoffTest {

    private static final int RACES = 500;

    @Test
    @DisplayName("줄이 먼저 생기면 포기하지 못하고 그 줄을 돌려준다")
    void cannotGiveUpOnceRowExistsAndReturnsRow() {
        Handoff handoff = new Handoff();
        AgentExecution row = mock(AgentExecution.class);

        handoff.onRowCreated(row);

        assertThat(handoff.abandon()).as("줄이 생긴 뒤의 포기").isFalse();
        assertThat(handoff.rowCreated()).isTrue();
        assertThat(handoff.abandoned()).isFalse();
        assertThat(handoff.execution()).isSameAs(row);
        assertThat(handoff.awaitRowDecided(deadlineAfter(Duration.ZERO)))
                .as("줄이 정해졌다")
                .isTrue();
    }

    @Test
    @DisplayName("포기한 뒤에 줄이 생기면 포기한 상태로 남는다")
    void staysGivenUpWhenRowAppearsAfterGivingUp() {
        Handoff handoff = new Handoff();

        assertThat(handoff.abandon()).isTrue();
        handoff.onRowCreated(mock(AgentExecution.class));

        assertThat(handoff.abandoned()).as("실행 스레드가 보고 제출하지 않는 표시").isTrue();
        assertThat(handoff.rowCreated()).as("요청 스레드는 줄을 돌려주지 않는다").isFalse();
    }

    @Test
    @DisplayName("줄 없이 끝나면 그 예외를 남기고 포기할 것도 없다")
    void keepsExceptionAndNothingToGiveUpWhenEndsWithoutRow() {
        Handoff handoff = new Handoff();
        RuntimeException cause = new IllegalStateException("저장 실패");

        handoff.markEnded(cause);

        assertThat(handoff.failure()).isSameAs(cause);
        assertThat(handoff.rowCreated()).isFalse();
        assertThat(handoff.abandon()).as("이미 끝난 뒤의 포기").isFalse();
        assertThat(handoff.abandoned()).isFalse();
        assertThat(handoff.awaitRowDecided(deadlineAfter(Duration.ZERO))).isTrue();
        assertThat(handoff.awaitSettled(deadlineAfter(Duration.ZERO))).isTrue();
        assertThat(handoff.submitted()).isFalse();
    }

    @Test
    @DisplayName("제출하면 제출 대기가 열린다")
    void submitOpensSubmitWait() {
        Handoff handoff = new Handoff();
        handoff.onRowCreated(mock(AgentExecution.class));

        handoff.markSubmitted();

        assertThat(handoff.awaitSettled(deadlineAfter(Duration.ZERO))).isTrue();
        assertThat(handoff.submitted()).isTrue();
    }

    @Test
    @DisplayName("아무것도 정해지지 않으면 마감 시각에 기다리기를 그만둔다")
    void stopsWaitingAtDeadlineWhenNothingIsDecided() {
        Handoff handoff = new Handoff();

        assertThat(handoff.awaitRowDecided(deadlineAfter(Duration.ofMillis(20))))
                .isFalse();
        assertThat(handoff.awaitSettled(deadlineAfter(Duration.ofMillis(20)))).isFalse();
    }

    @Test
    @DisplayName("포기와 줄 생성이 겹치면 꼭 하나만 이긴다")
    void exactlyOneWinsWhenGiveUpAndRowCreationOverlap() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACES; i++) {
                Handoff handoff = new Handoff();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> abandoned = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return handoff.abandon();
                });
                Future<?> created = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    handoff.onRowCreated(mock(AgentExecution.class));
                    return null;
                });
                boolean requestGaveUp = abandoned.get(5, TimeUnit.SECONDS);
                created.get(5, TimeUnit.SECONDS);

                assertThat(requestGaveUp).as("%d번째 경합: 포기가 이겼으면 줄은 돌려주지 않는다", i).isNotEqualTo(handoff.rowCreated());
                assertThat(handoff.abandoned())
                        .as("%d번째 경합: 실행 스레드가 보는 포기 표시", i)
                        .isEqualTo(requestGaveUp);
            }
        }
    }

    private static long deadlineAfter(Duration wait) {
        return System.nanoTime() + wait.toNanos();
    }
}
