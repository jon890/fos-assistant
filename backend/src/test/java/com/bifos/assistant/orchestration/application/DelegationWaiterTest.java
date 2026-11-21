package com.bifos.assistant.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.orchestration.application.dto.DelegationWait;
import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelegationWaiterTest {

    private static final Long ROOT_ID = 10L;
    private static final Long CHILD_ID = 11L;

    private final AgentDelegationService delegations = mock(AgentDelegationService.class);
    private final TurnCancellation turns = mock(TurnCancellation.class);
    private final ProactiveCheckGuard checkGuard = mock(ProactiveCheckGuard.class);
    private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-21T00:00:00Z"));
    private Duration waitMax = Duration.ofMinutes(2);
    private DelegationWaiter waiter;


    @BeforeEach
    void setUp() {
        LiveProperties<DelegationProperties> properties = LiveProperties.fixed(DelegationProperties.class,
            new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 100, Duration.ofSeconds(20), waitMax));
        waiter = new DelegationWaiter(delegations, turns, checkGuard, properties, executions, clock);
    }


    @Test
    @DisplayName("한 루트의 예산은 처음 기다린 때부터 흐른 시간으로 줄고, 상한이 지나면 0 이며, 오래된 항목은 지워진다")
    void budgetShrinksFromFirstWaitAndExpires() {
        Duration max = Duration.ofMinutes(2);

        assertThat(waiter.remaining(ROOT_ID, max)).isEqualTo(max);
        clock.advance(Duration.ofSeconds(30));
        assertThat(waiter.remaining(ROOT_ID, max)).isEqualTo(Duration.ofSeconds(90));
        assertThat(waiter.remaining(99L, max)).as("다른 루트는 따로 센다").isEqualTo(max);
        clock.advance(Duration.ofMinutes(2));
        assertThat(waiter.remaining(ROOT_ID, max)).isZero();
        clock.advance(Duration.ofMinutes(3));
        assertThat(waiter.remaining(ROOT_ID, max)).as("지운 뒤 새 답의 루트처럼 다시 센다").isEqualTo(max);
        assertThat(waiter.remaining(ROOT_ID, Duration.ZERO)).isZero();
    }


    @Test
    @DisplayName("대화 turn 의 루트가 맡긴 실행이 끝나면 다시 읽은 줄을 전해도 된다고 돌려준다")
    void returnsDeliverableWhenChildEndsWithinBudget() {
        RunningDelegation running = new RunningDelegation("http://hermes", "child");
        running.markEnded();
        when(delegations.running(CHILD_ID)).thenReturn(running);
        AgentExecution ended = child(ExecutionStatus.SUCCEEDED);

        DelegationWait waited = waiter.await(root(), CHILD_ID);

        assertThat(waited.execution()).isSameAs(ended);
        assertThat(waited.deliverable()).isTrue();
    }


    @Test
    @DisplayName("실패로 끝나도 결과로 전한다")
    void deliversFailedResult() {
        RunningDelegation running = new RunningDelegation("http://hermes", "child");
        running.markEnded();
        when(delegations.running(CHILD_ID)).thenReturn(running);
        child(ExecutionStatus.FAILED);

        assertThat(waiter.await(root(), CHILD_ID).deliverable()).isTrue();
    }


    @Test
    @DisplayName("상한이 0 이거나 손자 위임이거나 먼저 살펴보기 트리면 기다리지 않고 전하지 않는다")
    void doesNotWaitOutsideTarget() {
        RunningDelegation running = new RunningDelegation("http://hermes", "child");
        when(delegations.running(CHILD_ID)).thenReturn(running);
        child(ExecutionStatus.RUNNING);

        AgentExecution grandParent = mock(AgentExecution.class);
        when(grandParent.parentExecutionId()).thenReturn(5L);
        assertThat(waiter.await(grandParent, CHILD_ID).deliverable()).isFalse();

        AgentExecution check = root();
        when(checkGuard.isCheckTree(check)).thenReturn(true);
        assertThat(waiter.await(check, CHILD_ID).deliverable()).isFalse();

        waitMax = Duration.ZERO;
        setUp();
        long started = System.nanoTime();
        assertThat(waiter.await(root(), CHILD_ID).deliverable()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }


    @Test
    @DisplayName("루트 turn 에 중지 표시가 서면 곧 풀리고, 끝난 결과도 전하지 않는다")
    void releasesAndDoesNotDeliverWhenRootStopped() {
        RunningDelegation running = new RunningDelegation("http://hermes", "child");
        running.markEnded();
        when(delegations.running(CHILD_ID)).thenReturn(running);
        child(ExecutionStatus.CANCELLED);
        when(turns.isCancelled(ROOT_ID)).thenReturn(true);

        DelegationWait waited = waiter.await(root(), CHILD_ID);

        assertThat(waited.deliverable()).isFalse();
        assertThat(waited.execution().status()).isEqualTo(ExecutionStatus.CANCELLED);
    }


    @Test
    @DisplayName("예산이 다 되면 도는 실행을 RUNNING 으로 돌려주고 전하지 않는다")
    void returnsRunningWhenBudgetRunsOut() {
        waitMax = Duration.ofMillis(1);
        setUp();
        when(delegations.running(CHILD_ID)).thenReturn(new RunningDelegation("http://hermes", "child"));
        child(ExecutionStatus.RUNNING);
        clock.autoAdvance(Duration.ofMillis(5));

        DelegationWait waited = waiter.await(root(), CHILD_ID);

        assertThat(waited.deliverable()).isFalse();
        assertThat(waited.execution().status()).isEqualTo(ExecutionStatus.RUNNING);
    }


    private AgentExecution root() {
        AgentExecution origin = mock(AgentExecution.class);
        when(origin.parentExecutionId()).thenReturn(null);
        when(origin.treeRootId()).thenReturn(ROOT_ID);
        return origin;
    }


    private AgentExecution child(ExecutionStatus status) {
        AgentExecution execution = mock(AgentExecution.class);
        when(execution.status()).thenReturn(status);
        when(executions.findById(CHILD_ID)).thenReturn(Optional.of(execution));
        return execution;
    }


    /** 테스트가 옮기는 시계다. {@link #autoAdvance} 를 주면 읽을 때마다 그만큼 간다. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;
        private Duration step = Duration.ZERO;


        MovableClock(Instant start) {
            now = new AtomicReference<>(start);
        }


        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }


        void autoAdvance(Duration duration) {
            step = duration;
        }


        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }


        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }


        @Override
        public Instant instant() {
            return now.getAndUpdate(value -> value.plus(step));
        }
    }
}
