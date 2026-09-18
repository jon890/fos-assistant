package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;

/**
 * turn 하나를 Hermes runtime 에 제출하고 끝날 때까지 기다린다.
 *
 * <p>인터페이스로 둔 것은 실제 Hermes 없이 Control Plane 을 검사하기 위해서이고, 스트리밍 구현이
 * 부르는 쪽을 건드리지 않고 polling 구현을 대신하기 위해서다.
 */
public interface HermesRunsClient {

    String submit(HermesRunCommand command);

    HermesRunResult awaitCompletion(HermesRunCommand command, String runId);

    /**
     * 그 세션이 마지막으로 실제로 쓴 provider 와 모델을 읽는다.
     *
     * <p>읽지 못하면 null 이다. 모델 이름을 모르는 것이 답을 버릴 이유가 되지 않으므로 부르는 쪽은
     * null 을 받아도 실행을 성공으로 남긴다.
     */
    default SessionRuntime readSessionRuntime(
            String apiBaseUrl, String profileName, String sessionId) {
        return null;
    }

    default HermesRunResult runToCompletion(HermesRunCommand command) {
        return awaitCompletion(command, submit(command));
    }
}
