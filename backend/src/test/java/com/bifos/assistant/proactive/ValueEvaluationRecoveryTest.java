package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
        order.verify(store).findRunningIds();
        order.verify(limiter)
                .holdUntilRemoteEnds(
                        72L, 71L, "http://hermes.example.com/p/decision-test", "decision-test", "run-system", false);
        order.verify(recorder).fail(running, "DECISION_INTERRUPTED");
    }

    @Test
    @DisplayName("한 평가나 실행의 복구가 실패해도 다음 줄을 닫고 서버 기동을 이어 간다")
    void isolatesFailuresOfIndividualRows() {
        ValueEvaluationStore store = mock(ValueEvaluationStore.class);
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        ExecutionRecorder recorder = mock(ExecutionRecorder.class);
        AgentExecution bad = mock(AgentExecution.class);
        AgentExecution good = mock(AgentExecution.class);
        when(store.findRunningIds()).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("synthetic conversion failure")).when(store).recover(1L);
        when(executions.findByAgentIdIsNullAndConversationIdIsNullAndStatus(any())).thenReturn(List.of(bad, good));
        when(recorder.fail(bad, "DECISION_INTERRUPTED")).thenThrow(new IllegalStateException("synthetic update failure"));
        ValueEvaluationRecovery recovery = new ValueEvaluationRecovery(store, executions, recorder,
                mock(UserExecutionLimiter.class), mock(HermesProperties.class));
        assertThatCode(recovery::start).doesNotThrowAnyException();
        assertThat(recovery.isRunning()).isTrue();
        verify(store).recover(2L);
        verify(recorder).fail(good, "DECISION_INTERRUPTED");
    }

    @Test
    @DisplayName("복구 목록 조회가 실패해도 다른 복구를 시도하고 기동을 이어 간다")
    void continuesStartupAfterRepositoryFailure() {
        ValueEvaluationStore store = mock(ValueEvaluationStore.class);
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        when(store.findRunningIds()).thenThrow(new IllegalStateException("synthetic database failure"));
        when(executions.findByAgentIdIsNullAndConversationIdIsNullAndStatus(any()))
                .thenThrow(new IllegalStateException("synthetic database failure"));
        ValueEvaluationRecovery recovery = new ValueEvaluationRecovery(store, executions,
                mock(ExecutionRecorder.class), mock(UserExecutionLimiter.class), mock(HermesProperties.class));
        assertThatCode(recovery::start).doesNotThrowAnyException();
        assertThat(recovery.isRunning()).isTrue();
        verify(executions).findByAgentIdIsNullAndConversationIdIsNullAndStatus(any());
    }
}
