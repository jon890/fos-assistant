package com.bifos.assistant.proactive.application;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 중단된 판단을 재시작 때 실패로 닫는다. 새 모델 호출이나 replay 를 만들지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ValueEvaluationRecovery implements SmartLifecycle {

    private final ValueEvaluationStore store;
    private final AgentExecutionRepository executions;
    private final ExecutionRecorder recorder;
    private final UserExecutionLimiter limiter;
    private final HermesProperties hermes;
    private volatile boolean running;

    public void recover() {
        recoverEvaluations();
        recoverExecutions();
    }

    private void recoverEvaluations() {
        try {
            for (Long id : store.findRunningIds()) {
                try {
                    store.recover(id);
                } catch (RuntimeException ex) {
                    log.warn(
                            "가치 평가의 기동 복구를 건너뛴다 evaluationId={} error={}",
                            id,
                            ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("복구할 가치 평가 목록을 읽지 못했다 error={}", ex.getClass().getSimpleName());
        }
    }

    private void recoverExecutions() {
        try {
            for (AgentExecution execution :
                    executions.findByAgentIdIsNullAndConversationIdIsNullAndStatus(ExecutionStatus.RUNNING)) {
                try {
                    recoverExecution(execution);
                } catch (RuntimeException ex) {
                    log.warn(
                            "판단 실행의 기동 복구를 건너뛴다 executionId={} error={}",
                            execution.id(),
                            ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("복구할 판단 실행 목록을 읽지 못했다 error={}", ex.getClass().getSimpleName());
        }
    }

    private void recoverExecution(AgentExecution execution) {
        if (execution.hermesRunId() != null) {
            limiter.holdUntilRemoteEnds(
                    execution.userId(),
                    execution.id(),
                    hermes.profileBaseUrl(execution.profileName()),
                    execution.profileName(),
                    execution.hermesRunId(),
                    false);
        }
        recorder.fail(execution, "DECISION_INTERRUPTED");
    }

    @Override
    public void start() {
        try {
            recover();
        } catch (RuntimeException ex) {
            log.warn("가치 평가 기동 복구가 실패해도 서버 기동을 이어 간다 error={}", ex.getClass().getSimpleName());
        } finally {
            running = true;
        }
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
