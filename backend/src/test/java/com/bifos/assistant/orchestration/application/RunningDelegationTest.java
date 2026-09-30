package com.bifos.assistant.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 중지 표시와 run 번호 붙이기가 어떤 순서로 겹쳐도 Hermes 에 중지를 보내는 쪽이 꼭 하나인 것을 고정한다. */
class RunningDelegationTest {

    private static final int RACES = 500;
    private static final String RUN_ID = "run-1";

    @Test
    @DisplayName("중지가 먼저 오면 run 번호를 붙이는 쪽이 보낸다")
    void sideAttachingRunIdSendsWhenStopComesFirst() {
        RunningDelegation delegation = new RunningDelegation("http://agent-runtime.test", "worker");

        assertThat(delegation.requestStop()).as("아직 붙은 run 번호가 없다").isNull();
        assertThat(delegation.attachRun(RUN_ID)).as("붙이는 쪽이 중지를 보낸다").isTrue();
        assertThat(delegation.stopRequested()).isTrue();
        assertThat(delegation.runId()).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("run 번호가 먼저 붙으면 멈추는 쪽이 그 번호로 보낸다")
    void sideStoppingSendsByRunIdWhenIdIsAttachedFirst() {
        RunningDelegation delegation = new RunningDelegation("http://agent-runtime.test", "worker");

        assertThat(delegation.attachRun(RUN_ID)).as("중지 표시가 없어 붙이는 쪽은 보내지 않는다").isFalse();
        assertThat(delegation.requestStop()).isEqualTo(RUN_ID);
    }

    @Test
    @DisplayName("실행 번호는 줄이 생긴 뒤에 적히고 끝나면 기다리던 쪽이 깨어난다")
    void recordsRunIdAfterRowExistsAndWakesWaiterWhenDone() {
        RunningDelegation delegation = new RunningDelegation("http://agent-runtime.test", "worker");
        assertThat(delegation.executionId()).isNull();

        delegation.bindExecution(7L);
        delegation.markEnded();

        assertThat(delegation.executionId()).isEqualTo(7L);
        long before = System.nanoTime();
        delegation.awaitEnded(Duration.ofSeconds(5));
        assertThat(Duration.ofNanos(System.nanoTime() - before)).as("이미 끝나 기다리지 않는다").isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("중지와 run 번호 붙이기가 겹치면 보내는 쪽이 꼭 하나다")
    void exactlyOneSenderWhenStopAndRunIdAttachOverlap() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACES; i++) {
                RunningDelegation delegation = new RunningDelegation("http://agent-runtime.test", "worker");
                CyclicBarrier start = new CyclicBarrier(2);
                Future<String> stopped = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return delegation.requestStop();
                });
                Future<Boolean> attached = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return delegation.attachRun(RUN_ID);
                });
                boolean stopperSends = stopped.get(5, TimeUnit.SECONDS) != null;
                boolean attacherSends = attached.get(5, TimeUnit.SECONDS);

                assertThat(stopperSends).as("%d번째 경합: 멈추는 쪽과 붙이는 쪽 가운데 하나만 보낸다", i)
                        .isNotEqualTo(attacherSends);
            }
        }
    }
}
