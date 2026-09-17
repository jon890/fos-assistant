package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 실제 Hermes Runtime 없이 Control Plane 을 검사하는 대역이다. */
public class StubHermesRunsClient implements HermesRunsClient {

    private final List<HermesRunCommand> received = new ArrayList<>();
    private HermesRunResult nextResult;
    private final Deque<HermesRunResult> queuedResults = new ArrayDeque<>();
    private final Map<String, HermesRunResult> submittedResults = new HashMap<>();
    private ApiException nextFailure;
    private String submittedRunId;
    private Runnable beforeAwait = () -> {};

    public void willReturn(HermesRunResult result) {
        this.nextResult = result;
        this.nextFailure = null;
    }

    /** 대화 실행과 후속 실행처럼 순서가 있는 결과를 차례로 돌려준다. */
    public void willReturnInOrder(HermesRunResult... results) {
        queuedResults.clear();
        java.util.Collections.addAll(queuedResults, results);
        nextFailure = null;
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
        queuedResults.clear();
        submittedResults.clear();
        nextFailure = null;
        submittedRunId = null;
        beforeAwait = () -> {};
    }

    @Override
    public String submit(HermesRunCommand command) {
        received.add(command);
        if (nextFailure != null) {
            throw nextFailure;
        }
        HermesRunResult result = queuedResults.isEmpty() ? nextResult : queuedResults.removeFirst();
        submittedRunId = result == null ? "run-stub" : result.runId();
        if (result != null) submittedResults.put(submittedRunId, result);
        return submittedRunId;
    }

    @Override
    public HermesRunResult awaitCompletion(HermesRunCommand command, String runId) {
        beforeAwait.run();
        if (nextFailure != null) {
            throw nextFailure;
        }
        return submittedResults.getOrDefault(runId, nextResult);
    }
}
