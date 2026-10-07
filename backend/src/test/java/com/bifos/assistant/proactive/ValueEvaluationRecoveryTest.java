package com.bifos.assistant.proactive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.proactive.application.ValueEvaluationRecovery;
import com.bifos.assistant.proactive.application.ValueEvaluationStore;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueEvaluationRecoveryTest {

    @Test
    @DisplayName("시스템 실행은 사용자 에이전트 없이도 재시작 때 원격 자리를 쥔 뒤 실패로 닫는다")
    void holdsSystemRunBeforeClosingItsExecution() {
        ValueEvaluationStore store = mock(ValueEvaluationStore.class);
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        ExecutionRecorder recorder = mock(ExecutionRecorder.class);
        UserExecutionLimiter limiter = mock(UserExecutionLimiter.class);
        AgentExecution running = mock(AgentExecution.class);
        when(running.id()).thenReturn(71L);
        when(running.userId()).thenReturn(72L);
        when(running.profileName()).thenReturn("decision-test");
        when(running.hermesRunId()).thenReturn("run-system");
        when(executions.findByAgentIdIsNullAndConversationIdIsNullAndStatus(any()))
                .thenReturn(List.of(running));
        HermesProperties properties = new HermesProperties(
                null, "http://hermes.example.com", "synthetic", "http://hermes.example.com", null, null, null, null);

        new ValueEvaluationRecovery(store, executions, recorder, limiter, properties).recover();

        var order = inOrder(store, limiter, recorder);
        order.verify(store).recover();
        order.verify(limiter)
                .holdUntilRemoteEnds(
                        72L, 71L, "http://hermes.example.com/p/decision-test", "decision-test", "run-system", false);
        order.verify(recorder).fail(running, "DECISION_INTERRUPTED");
    }
}
