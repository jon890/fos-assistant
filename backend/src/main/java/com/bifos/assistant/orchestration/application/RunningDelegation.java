package com.bifos.assistant.orchestration.application;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link AgentDelegationService} 가 돌리는 위임 실행 하나의 중지 표시와 run 참조다.
 *
 * <p>「중지 표시를 켠다」 와 「run 번호를 붙인다」 는 같은 잠금 안에서 한다. 따로 하면 중지가 run 번호를 못 본 직후
 * 번호가 붙고, 붙이는 쪽도 중지 표시를 못 봐 누구도 Hermes 에 중지를 보내지 않는다. 한 잠금 안에서 하면 둘 가운데
 * 늦은 쪽이 앞의 것을 보고 한 번만 보낸다.
 */
final class RunningDelegation {

    private final String apiBaseUrl;
    private final String profileName;
    /** 실행 줄이 생기면 적힌다. */
    private volatile Long executionId;
    private String runId;
    private boolean stopRequested;
    /** 실행이 상태를 적고 끝나면 열린다. */
    private final CountDownLatch ended = new CountDownLatch(1);

    RunningDelegation(String apiBaseUrl, String profileName) {
        this.apiBaseUrl = apiBaseUrl;
        this.profileName = profileName;
    }

    String apiBaseUrl() {
        return apiBaseUrl;
    }

    String profileName() {
        return profileName;
    }

    Long executionId() {
        return executionId;
    }

    /** 실행 줄이 생긴 직후 그 번호를 적는다. */
    void bindExecution(Long id) {
        executionId = id;
    }

    /** 중지 표시를 켠다. 이미 붙은 run 번호가 있으면 그것을 돌려주고, 부르는 쪽이 Hermes 에 중지를 보낸다. */
    synchronized String requestStop() {
        stopRequested = true;
        return runId;
    }

    /** run 번호를 붙인다. 중지 표시가 먼저 켜졌으면 참이고, 부르는 쪽이 Hermes 에 중지를 보낸다. */
    synchronized boolean attachRun(String submittedRunId) {
        runId = submittedRunId;
        return stopRequested;
    }

    synchronized boolean stopRequested() {
        return stopRequested;
    }

    synchronized String runId() {
        return runId;
    }

    void markEnded() {
        ended.countDown();
    }

    /** 실행이 끝나기를 기다린다. 끊기면 기다리기를 그만두고 끊긴 표시는 되살린다. */
    void awaitEnded(Duration limit) {
        try {
            ended.await(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
