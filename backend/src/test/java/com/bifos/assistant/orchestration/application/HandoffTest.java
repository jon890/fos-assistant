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
import org.junit.jupiter.api.Test;

/** 요청 스레드의 포기와 실행 스레드의 줄 생성 가운데 하나만 이기는 것을 고정한다. */
class HandoffTest {

    private static final int RACES = 500;

    @Test
    void 줄이_먼저_생기면_포기하지_못하고_그_줄을_돌려준다() {
        Handoff handoff = new Handoff();
        AgentExecution row = mock(AgentExecution.class);

        handoff.onRowCreated(row);

        assertThat(handoff.abandon()).as("줄이 생긴 뒤의 포기").isFalse();
        assertThat(handoff.rowCreated()).isTrue();
        assertThat(handoff.abandoned()).isFalse();
        assertThat(handoff.execution()).isSameAs(row);
        assertThat(handoff.awaitRowDecided(deadlineAfter(Duration.ZERO))).as("줄이 정해졌다").isTrue();
    }

    @Test
    void 포기한_뒤에_줄이_생기면_포기한_상태로_남는다() {
        Handoff handoff = new Handoff();

        assertThat(handoff.abandon()).isTrue();
        handoff.onRowCreated(mock(AgentExecution.class));

        assertThat(handoff.abandoned()).as("실행 스레드가 보고 제출하지 않는 표시").isTrue();
        assertThat(handoff.rowCreated()).as("요청 스레드는 줄을 돌려주지 않는다").isFalse();
    }

    @Test
    void 줄_없이_끝나면_그_예외를_남기고_포기할_것도_없다() {
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
    void 제출하면_제출_대기가_열린다() {
        Handoff handoff = new Handoff();
        handoff.onRowCreated(mock(AgentExecution.class));

        handoff.markSubmitted();

        assertThat(handoff.awaitSettled(deadlineAfter(Duration.ZERO))).isTrue();
        assertThat(handoff.submitted()).isTrue();
    }

    @Test
    void 아무것도_정해지지_않으면_마감_시각에_기다리기를_그만둔다() {
        Handoff handoff = new Handoff();

        assertThat(handoff.awaitRowDecided(deadlineAfter(Duration.ofMillis(20)))).isFalse();
        assertThat(handoff.awaitSettled(deadlineAfter(Duration.ofMillis(20)))).isFalse();
    }

    @Test
    void 포기와_줄_생성이_겹치면_꼭_하나만_이긴다() throws Exception {
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

                assertThat(requestGaveUp).as("%d번째 경합: 포기가 이겼으면 줄은 돌려주지 않는다", i)
                        .isNotEqualTo(handoff.rowCreated());
                assertThat(handoff.abandoned()).as("%d번째 경합: 실행 스레드가 보는 포기 표시", i).isEqualTo(requestGaveUp);
            }
        }
    }

    private static long deadlineAfter(Duration wait) {
        return System.nanoTime() + wait.toNanos();
    }
}
