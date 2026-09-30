package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.shared.error.ApiException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * 실제 Hermes Runtime 없이 Control Plane 을 검사하는 대역이다.
 *
 * <p>여러 스레드가 함께 부른다. 흐름이 Researcher 와 Engineer 를 나란히 띄우므로, 받은 명령과 제출한
 * 실행을 동시에 쓸 수 있는 자료 구조에 담는다.
 */
public class StubHermesRunsClient implements HermesRunsClient {

    /** 붙잡은 제출이 풀리지 않아도 이어지는 시간이다. */
    private static final Duration SUBMIT_HOLD_LIMIT = Duration.ofSeconds(10);

    private final List<HermesRunCommand> received = new CopyOnWriteArrayList<>();
    private volatile HermesRunResult nextResult;
    private final Deque<HermesRunResult> queuedResults = new ArrayDeque<>();
    private final Map<String, HermesRunResult> submittedResults = new ConcurrentHashMap<>();
    private volatile Function<HermesRunCommand, HermesRunResult> answer;
    private volatile ApiException nextFailure;
    private volatile Runnable beforeAwait = () -> {};
    private final List<String> stopped = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> onStop = runId -> {};

    /** 비어 있지 않으면 제출이 이것이 열릴 때까지 멈춘다. */
    private volatile CountDownLatch submitGate;

    /** 세션 조회가 답할 값이다. 비어 있으면 읽지 못한 것으로 본다. */
    private volatile SessionRuntime sessionRuntime;
    private final List<String> sessionLookups = new CopyOnWriteArrayList<>();

    public void willReturn(HermesRunResult result) {
        this.nextResult = result;
        this.nextFailure = null;
    }

    /** 대화 실행과 후속 실행처럼 순서가 있는 결과를 차례로 돌려준다. */
    public void willReturnInOrder(HermesRunResult... results) {
        synchronized (queuedResults) {
            queuedResults.clear();
            Collections.addAll(queuedResults, results);
        }
        nextFailure = null;
    }

    /**
     * 받은 지시를 보고 결과를 정한다.
     *
     * <p>나란히 도는 실행은 순서가 정해지지 않아 {@link #willReturnInOrder} 로는 무엇이 무엇의 답인지
     * 고정할 수 없다. 지시로 고르면 순서와 무관하게 같은 답이 돌아온다.
     */
    public void willAnswer(Function<HermesRunCommand, HermesRunResult> answer) {
        this.answer = answer;
        this.nextFailure = null;
    }

    /** 세션 조회가 실제로 돈 provider 와 모델을 이렇게 답하게 한다. */
    public void willReportSessionRuntime(SessionRuntime runtime) {
        this.sessionRuntime = runtime;
    }

    /** 세션 조회를 부른 session 번호들. */
    public List<String> sessionLookups() {
        return sessionLookups;
    }

    public void willFail(ApiException failure) {
        this.nextFailure = failure;
        this.nextResult = null;
    }

    public List<HermesRunCommand> received() {
        return received;
    }

    /** 완료를 기다리기 직전에 실행해 제출 뒤 저장된 상태를 검사한다. */
    public void beforeAwait(Runnable action) {
        this.beforeAwait = action;
    }

    /**
     * 이 뒤의 제출을 {@link #releaseSubmits()} 까지 붙잡는다. 제출을 기다리는 쪽의 제한 시간을 검사할 때 쓴다.
     *
     * <p>받은 명령은 붙잡기 전에 남긴다. 풀지 않아도 {@link #SUBMIT_HOLD_LIMIT} 가 지나면 제출이 이어져 스레드가 검사보다
     * 오래 살지 않는다.
     */
    public void holdSubmits() {
        submitGate = new CountDownLatch(1);
    }

    /** 붙잡은 제출을 모두 놓는다. */
    public void releaseSubmits() {
        CountDownLatch gate = submitGate;
        submitGate = null;
        if (gate != null) {
            gate.countDown();
        }
    }

    public void onStop(Consumer<String> action) { this.onStop = action; }
    public List<String> stopped() { return stopped; }

    public void reset() {
        received.clear();
        nextResult = null;
        synchronized (queuedResults) {
            queuedResults.clear();
        }
        submittedResults.clear();
        answer = null;
        nextFailure = null;
        beforeAwait = () -> {};
        sessionRuntime = null;
        sessionLookups.clear();
        stopped.clear();
        onStop = runId -> {};
        releaseSubmits();
    }

    @Override
    public String submit(HermesRunCommand command) {
        received.add(command);
        awaitGate();
        if (nextFailure != null) {
            throw nextFailure;
        }
        HermesRunResult result = resultFor(command);
        String runId = result == null ? "run-stub" : result.runId();
        if (result != null) {
            submittedResults.put(runId, result);
        }
        return runId;
    }

    @Override
    public HermesRunResult awaitCompletion(HermesRunCommand command, String runId) {
        beforeAwait.run();
        if (nextFailure != null) {
            throw nextFailure;
        }
        return submittedResults.getOrDefault(runId, nextResult);
    }

    @Override
    public void stop(String apiBaseUrl, String profileName, String runId) {
        stopped.add(runId);
        onStop.accept(runId);
    }

    @Override
    public SessionRuntime readSessionRuntime(String apiBaseUrl, String profileName, String sessionId) {
        sessionLookups.add(sessionId);
        return sessionRuntime;
    }

    private void awaitGate() {
        CountDownLatch gate = submitGate;
        if (gate == null) {
            return;
        }
        try {
            gate.await(SUBMIT_HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private HermesRunResult resultFor(HermesRunCommand command) {
        if (answer != null) {
            return answer.apply(command);
        }
        synchronized (queuedResults) {
            if (!queuedResults.isEmpty()) {
                return queuedResults.removeFirst();
            }
        }
        return nextResult;
    }
}
