package com.bifos.assistant.proactive.application;

import static com.bifos.assistant.proactive.application.ProactiveCheckRun.STOP_ATTEMPTS;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.STOP_RETRY_INTERVAL;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.TIME_LIMIT;
import static com.bifos.assistant.proactive.application.ProactiveCheckRun.TOOL_LIMIT;

import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/** 살펴보기의 시간·도구 상한과 닫힘 잠금, 멈추기 재시도를 한 실행 안에서 지킨다. */
@Slf4j
class ProactiveCheckLimits {
    private final CurrentUser owner;
    private final ProactiveCheck check;
    private final ProactiveCheckRun.Deps deps;

    final AtomicInteger toolCalls = new AtomicInteger();

    /**
     * 상한으로 멈춘 까닭. 멈추기를 부르는 동안과 멈춘 뒤에만 있다. 멈추기가 실패하면 비우고, 사용자가 멈췄거나 멈추지 않았으면 비어
     * 있다.
     */
    final AtomicReference<String> stopReason = new AtomicReference<>();

    /** 닫힘 표시와 시간 상한 스레드, 멈추기 시도 수를 함께 바꾸고 읽는 잠금이다. 상한 판정이 닫힌 뒤에 끼어들지 않게 한다. */
    private final Object limitLock = new Object();

    private boolean closed;
    private Thread timeLimit;
    private int stopAttempts;

    ProactiveCheckLimits(CurrentUser owner, ProactiveCheck check, ProactiveCheckRun.Deps deps) {
        this.owner = owner;
        this.check = check;
        this.deps = deps;
    }

    /** 루트 실행을 살펴보기 줄에 적은 뒤 {@code max-duration} 이 지나면 깨는 시간 상한 스레드를 띄운다. */
    void started(Long executionId, String hermesRootSessionId) {
        check.attachRoot(executionId, hermesRootSessionId);
        deps.checks().save(check);
        Thread timer = Thread.ofVirtual()
                .name("proactive-check-time-limit-" + executionId)
                .unstarted(() -> awaitTimeLimit(executionId));
        synchronized (limitLock) {
            if (closed) {
                return;
            }
            timeLimit = timer;
        }
        timer.start();
    }

    /** 스트림을 읽는 스레드에서 불린다. 센 값이 {@code max-tool-calls} 를 넘는 첫 순간 멈춘다. */
    void toolStarted(Long executionId) {
        if (toolCalls.incrementAndGet() > deps.properties().maxToolCalls()) {
            limitReached(TOOL_LIMIT, executionId);
        }
    }

    /** 시간 상한 스레드를 깨워 끝내고, 그 뒤로는 상한에 닿아도 멈추기를 부르지 않는다. 줄을 적기 전에 부른다. */
    void close() {
        Thread timer;
        synchronized (limitLock) {
            closed = true;
            timer = timeLimit;
        }
        if (timer != null) {
            timer.interrupt();
        }
    }

    /** {@code max-duration} 만큼 잔 뒤 끝나지 않았으면 멈춘다. {@link #close} 가 깨우면 그대로 끝난다. */
    private void awaitTimeLimit(Long executionId) {
        try {
            Thread.sleep(deps.properties().maxDuration());
        } catch (InterruptedException ex) {
            return;
        }
        limitReached(TIME_LIMIT, executionId);
    }

    /**
     * 멈춘 까닭을 정하고 가상 스레드에서 멈춘다. 닫혔거나, 이미 까닭이 있거나, 멈추기 시도를 다 썼으면 아무것도 하지 않는다.
     *
     * <p>도구 호출 수는 스트림을 읽는 스레드가 turn 의 잠금을 쥔 채 센다. 그 자리에서 멈추기를 부르지 않고 따로 띄운다.
     */
    private void limitReached(String reason, Long executionId) {
        if (claimStop(reason)) {
            Thread.ofVirtual()
                    .name("proactive-check-stop-" + executionId)
                    .start(() -> stopWithRetry(reason, executionId));
        }
    }

    /**
     * 멈추기 시도 하나를 쓴다. 닫히지 않았고 시도가 남았고 까닭이 비어 있을 때만 까닭을 정하고 참을 돌려준다.
     *
     * <p>시도 수는 살펴보기 하나에서 센다. 도구 상한은 실패 뒤 다음 {@code tool.started} 에서도 다시 부르므로, 도구 호출이 이어져도
     * 멈추기를 끝없이 부르지 않게 한다.
     */
    private boolean claimStop(String reason) {
        synchronized (limitLock) {
            if (closed || stopAttempts >= STOP_ATTEMPTS || !stopReason.compareAndSet(null, reason)) {
                return false;
            }
            stopAttempts++;
            return true;
        }
    }

    /**
     * 멈추기를 부르고, 실패하면 정한 까닭을 되돌린 뒤 {@link ProactiveCheckRun#STOP_RETRY_INTERVAL} 뒤에 다시 시도한다. 까닭이 남아 있는 동안 turn 이
     * 예외로 끝나면 상한으로 멈춘 것으로 적히므로, 멈추지 못한 채 실패한 turn 이 상한으로 기록되지 않게 바로 되돌린다.
     *
     * <p>되돌릴 때는 자기가 정한 까닭일 때만 비운다. 이미 끝난 turn 이면 멈추기가 {@code EXECUTION_NOT_RUNNING} 으로 끝나고, 닫혔으니
     * 다시 시도하지 않는다.
     */
    private void stopWithRetry(String reason, Long executionId) {
        do {
            try {
                deps.chat().stop(owner, executionId);
                return;
            } catch (RuntimeException ex) {
                stopReason.compareAndSet(reason, null);
                log.warn("상한에 닿은 살펴보기를 멈추지 못했다 executionId={} reason={}", executionId, reason, ex);
            }
            try {
                Thread.sleep(STOP_RETRY_INTERVAL);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (claimStop(reason));
    }
}
