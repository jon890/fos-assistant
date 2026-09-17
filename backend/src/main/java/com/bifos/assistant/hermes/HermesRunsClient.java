package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;

/**
 * Submits a turn to the Hermes runtime and waits for it to settle.
 *
 * <p>Kept as an interface so tests can drive the Control Plane without a live Hermes, and so a
 * streaming implementation can replace the polling one without touching callers.
 */
public interface HermesRunsClient {

    String submit(HermesRunCommand command);

    HermesRunResult awaitCompletion(HermesRunCommand command, String runId);

    default HermesRunResult runToCompletion(HermesRunCommand command) {
        return awaitCompletion(command, submit(command));
    }
}
