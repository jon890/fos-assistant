package com.bifos.assistant.proactive.application;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 중단된 판단을 재시작 때 실패로 닫는다. 새 모델 호출이나 replay 를 만들지 않는다. */
@Component
@RequiredArgsConstructor
public class ValueEvaluationRecovery implements SmartLifecycle {

    private final ValueEvaluationStore store;
    private final AgentExecutionRepository executions;
    private final ExecutionRecorder recorder;
    private final UserExecutionLimiter limiter;
    private final HermesProperties hermes;
    private volatile boolean running;

    public void recover() {
        store.recover();
        for (AgentExecution execution : executions.findByAgentIdIsNullAndConversationIdIsNullAndStatus(ExecutionStatus.RUNNING)) {
            if (execution.hermesRunId() != null) {
                limiter.holdUntilRemoteEnds(execution.userId(), execution.id(), hermes.profileBaseUrl(execution.profileName()),
                        execution.profileName(), execution.hermesRunId(), false);
            }
            recorder.fail(execution, "DECISION_INTERRUPTED");
        }
    }

    @Override
    public void start() {
        recover();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
