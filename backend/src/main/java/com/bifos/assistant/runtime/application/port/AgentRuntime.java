package com.bifos.assistant.runtime.application.port;

import com.bifos.assistant.runtime.application.dto.RunCommand;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunLookup;
import com.bifos.assistant.runtime.application.dto.RunResult;
import com.bifos.assistant.runtime.application.dto.SessionRuntime;
import com.bifos.assistant.runtime.application.dto.SteerOutcome;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.runtime.domain.SubagentSessionUsage;
import com.bifos.assistant.runtime.domain.ToolDetailScope;
import java.io.Closeable;
import java.util.function.Consumer;

/**
 * turn 하나를 Agent Runtime 에 제출하고 그 실행을 다루는 port 다.
 *
 * <p>런타임 종류마다 구현이 하나 있고 {@code AgentRuntimes} 가 대상의 종류로 고른다. 하지 못하는 동작은 예외가 아니라 메서드마다
 * 정한 값으로 답해, 부르는 쪽이 런타임을 나눠 다루지 않게 한다. 근거는 ADR-20261121 / acp-runtime 에 있다.
 */
public interface AgentRuntime {

    /** 이 구현이 맡는 런타임의 종류다. */
    RuntimeKind kind();


    /** 실행을 보내고 그 번호를 받는다. */
    String submit(RunCommand command);


    /** 보낸 실행이 끝날 때까지 기다려 결과를 받는다. */
    RunResult awaitCompletion(RunCommand command, String runId);


    default RunResult runToCompletion(RunCommand command) {
        return awaitCompletion(command, submit(command));
    }


    /**
     * 그 실행의 사건을 끝날 때까지 받는다. 스트림이 끝나야 돌아온다.
     *
     * <p>구현은 {@code scope} 에 따라 도구의 내용을 가린 사건만 낸다. 가리지 않은 원문을 부르는 쪽에 넘기지 않는다.
     *
     * @param onOpened 스트림이 열리면 닫을 손잡이를 한 번 받는다. 부르는 쪽이 중지할 때 닫는다
     */
    void openEvents(
        RuntimeTarget target,
        String runId,
        Consumer<RunEvent> onEvent,
        Consumer<Closeable> onOpened,
        ToolDetailScope scope);


    /** 그 실행을 멈추라고 보낸다. */
    void stop(RuntimeTarget target, String runId);


    /**
     * 도는 실행에 글 하나를 끼워 넣으라고 보낸다.
     *
     * <p>받음은 런타임이 그 글을 받아 두었다는 뜻이지 모델이 읽었다는 뜻이 아니다. 끼우지 못하는 구현은 {@code REJECTED} 로
     * 답하고, 부르는 쪽은 그 글을 다음 turn 으로 넘긴다.
     */
    default SteerOutcome steer(RuntimeTarget target, String runId, String text) {
        return SteerOutcome.REJECTED;
    }


    /**
     * 그 session 과 메시지를 런타임에서 지운다.
     *
     * <p>런타임이 그 session 을 모르면 이미 지운 것으로 보고 조용히 끝낸다. session 을 지우지 못하는 구현은 아무것도 하지 않는다.
     * 닿지 못했거나 다른 오류면 ApiException 을 던진다.
     */
    void deleteSession(RuntimeTarget target, String sessionId);


    /**
     * 실행 하나의 지금 상태를 한 번 읽는다. 기다리지 않는다.
     *
     * <p>런타임이 그 실행을 모르거나 다시 물을 곳이 없는 구현이면 {@code NOT_FOUND} 다. 닿지 못했거나 다른 오류면
     * ApiException 을 던진다.
     */
    RunLookup lookupRun(RuntimeTarget target, String runId);


    /**
     * 그 세션이 마지막으로 실제로 쓴 provider 와 모델을 읽는다.
     *
     * <p>읽지 못하면 null 이다. 부르는 쪽은 null 을 받아도 실행을 성공으로 남긴다.
     */
    default SessionRuntime readSessionRuntime(RuntimeTarget target, String sessionId) {
        return null;
    }


    /**
     * 런타임이 그 실행을 재기동으로 잃은 것으로 알고 있으면 해제한다. 아니면 아무것도 하지 않는다.
     *
     * <p>잃는 실행이 없는 구현은 아무것도 하지 않는다. 닿지 못했거나 해제하지 못했으면 ApiException 을 던진다.
     */
    default void releaseIfLost(RuntimeTarget target, String runId) {
    }


    /** 하위 에이전트 session 의 사용량을 읽는다. 읽지 못했거나 하위 에이전트가 없는 구현이면 null 이다. */
    default SubagentSessionUsage readSubagentUsage(RuntimeTarget target, String sessionId) {
        return null;
    }
}
