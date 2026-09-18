package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 실제 Hermes Runtime 없이 Control Plane 을 검사하는 대역이다.
 *
 * <p>여러 스레드가 함께 부른다. 흐름이 Researcher 와 Engineer 를 나란히 띄우므로, 받은 명령과 제출한
 * 실행을 동시에 쓸 수 있는 자료 구조에 담는다.
 */
public class StubHermesRunsClient implements HermesRunsClient {

    private final List<HermesRunCommand> received = new CopyOnWriteArrayList<>();
    private volatile HermesRunResult nextResult;
    private final Deque<HermesRunResult> queuedResults = new ArrayDeque<>();
    private final Map<String, HermesRunResult> submittedResults = new ConcurrentHashMap<>();
    private volatile Function<HermesRunCommand, HermesRunResult> answer;
    private volatile ApiException nextFailure;
    private volatile Runnable beforeAwait = () -> {};

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
    }

    @Override
    public String submit(HermesRunCommand command) {
        received.add(command);
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
