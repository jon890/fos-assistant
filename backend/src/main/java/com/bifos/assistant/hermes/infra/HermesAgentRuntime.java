package com.bifos.assistant.hermes.infra;

import lombok.RequiredArgsConstructor;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.runtime.application.dto.RunCommand;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.RunResult;
import com.bifos.assistant.runtime.application.dto.SessionRuntime;
import com.bifos.assistant.runtime.application.dto.SteerOutcome;
import com.bifos.assistant.runtime.application.port.AgentRuntime;
import com.bifos.assistant.runtime.domain.RunWait;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.runtime.domain.SubagentSessionUsage;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import java.io.Closeable;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 실행 port 의 Hermes 구현이다.
 *
 * <p>대상의 {@code location} 은 그 profile 의 API server 가 답하는 주소({@code /v1} 앞까지)이고 {@code unit} 은 Hermes
 * profile 이름이다. Hermes 호출은 {@link HermesRunsClient} 와 {@link HermesRunEventStream} 이 하고, 여기서는 Hermes 의
 * 명령·결과·사건을 런타임에 묶이지 않는 타입과 서로 옮기기만 한다.
 */
@Component
@RequiredArgsConstructor
public class HermesAgentRuntime implements AgentRuntime {

    private final HermesRunsClient client;
    private final HermesRunEventStream eventStream;


    @Override
    public RuntimeKind kind() {
        return RuntimeKind.HERMES;
    }


    @Override
    public String submit(RunCommand command) {
        return client.submit(toHermes(command));
    }


    @Override
    public RunResult awaitCompletion(RunCommand command, String runId, RunWait wait) {
        return HermesRunOutcomes.toRunResult(client.awaitCompletion(toHermes(command), runId, wait));
    }


    @Override
    public RunResult runToCompletion(RunCommand command) {
        return HermesRunOutcomes.toRunResult(client.runToCompletion(toHermes(command)));
    }


    @Override
    public void openEvents(
        RuntimeTarget target,
        String runId,
        Consumer<RunEvent> onEvent,
        Consumer<Closeable> onOpened,
        ToolDetailScope scope) {
        eventStream.open(
            target.location(),
            target.unit(),
            runId,
            event -> onEvent.accept(HermesRunEventKinds.toRunEvent(event)),
            onOpened,
            scope);
    }


    @Override
    public void stop(RuntimeTarget target, String runId) {
        client.stop(target.location(), target.unit(), runId);
    }


    @Override
    public SteerOutcome steer(RuntimeTarget target, String runId, String text) {
        return client.steer(target.location(), target.unit(), runId, text);
    }


    @Override
    public void deleteSession(RuntimeTarget target, String sessionId) {
        client.deleteSession(target.location(), target.unit(), sessionId);
    }


    @Override
    public RunLookup lookupRun(RuntimeTarget target, String runId) {
        return HermesRunOutcomes.toRunLookup(client.lookupRun(target.location(), target.unit(), runId));
    }


    @Override
    public SessionRuntime readSessionRuntime(RuntimeTarget target, String sessionId) {
        return client.readSessionRuntime(target.location(), target.unit(), sessionId);
    }


    @Override
    public void releaseIfLost(RuntimeTarget target, String runId) {
        client.releaseIfLost(target.location(), target.unit(), runId);
    }


    @Override
    public SubagentSessionUsage readSubagentUsage(RuntimeTarget target, String sessionId) {
        return client.readSubagentUsage(target.location(), target.unit(), sessionId);
    }


    private static HermesRunCommand toHermes(RunCommand command) {
        return new HermesRunCommand(
            command.target().unit(),
            command.target().location(),
            command.input(),
            command.instructions(),
            command.sessionId(),
            command.provider(),
            command.model(),
            command.reasoningEffort(),
            command.images());
    }
}
