package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Lets the Control Plane be exercised without a live Hermes runtime. */
public class StubHermesRunsClient implements HermesRunsClient {

    private final List<HermesRunCommand> received = new ArrayList<>();
    private HermesRunResult nextResult;
    private ApiException nextFailure;

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

    public void reset() {
        received.clear();
        nextResult = null;
        nextFailure = null;
    }

    @Override
    public HermesRunResult runToCompletion(HermesRunCommand command) {
        received.add(command);
        if (nextFailure != null) {
            throw nextFailure;
        }
        return nextResult;
    }
}
