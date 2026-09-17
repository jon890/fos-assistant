package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;

/** 실제 Hermes Runtime 없이 Control Plane 을 검사하는 대역이다. */
public class StubHermesRunsClient implements HermesRunsClient {

    private final List<HermesRunCommand> received = new ArrayList<>();
    private HermesRunResult nextResult;
    private ApiException nextFailure;
    private String submittedRunId;
    private Runnable beforeAwait = () -> {};

    public void willReturn(HermesRunResult result) {
        this.nextResult = result;
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
        submittedRunId = nextResult == null ? "run-stub" : nextResult.runId();
        return submittedRunId;
    }

    @Override
    public HermesRunResult awaitCompletion(HermesRunCommand command, String runId) {
        beforeAwait.run();
        if (nextFailure != null) {
            throw nextFailure;
        }
        return nextResult;
    }
}
