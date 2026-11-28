package com.bifos.assistant.chat.recovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기동 정리가 상한을 설정하지 않았을 때 줄마다 처음 쓴 한도로 기다리는지 본다(docs/features/execution.md 의 「실행 한도」). */
class ReconcileWaitTest {

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration BACKGROUND_RUN_TIMEOUT = Duration.ofMinutes(20);

    private final HermesProperties hermes = new HermesProperties(
        "keys",
        "https://hermes-dashboard.example.com",
        "test-dashboard-token",
        "https://hermes-listener.example.com",
        Duration.ofMillis(700),
        RUN_TIMEOUT,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        BACKGROUND_RUN_TIMEOUT);


    @Test
    @DisplayName("사람이 보낸 대화 turn 의 루트 줄은 hermes.run-timeout 까지 기다린다")
    void conversationRootWaitsRunTimeout() {
        assertThat(ReconcileWait.of(row(null, null, false), hermes)).isEqualTo(RUN_TIMEOUT);
    }


    @Test
    @DisplayName("맡긴 실행과 사람이 없는 루트는 hermes.background-run-timeout 까지 기다린다")
    void backgroundRowsWaitLonger() {
        assertThat(ReconcileWait.of(row(1L, "key", false), hermes)).as("맡긴 자식").isEqualTo(BACKGROUND_RUN_TIMEOUT);
        assertThat(ReconcileWait.of(row(null, "key", false), hermes)).as("부모 없는 스킬 맡기기")
            .isEqualTo(BACKGROUND_RUN_TIMEOUT);
        assertThat(ReconcileWait.of(row(1L, null, false), hermes)).as("흐름의 자식").isEqualTo(BACKGROUND_RUN_TIMEOUT);
        assertThat(ReconcileWait.of(row(null, null, true), hermes)).as("예약 작업이 연 루트")
            .isEqualTo(BACKGROUND_RUN_TIMEOUT);
    }


    private static AgentExecution row(Long parentExecutionId, String delegationKey, boolean unattended) {
        AgentExecution row = mock(AgentExecution.class);
        when(row.parentExecutionId()).thenReturn(parentExecutionId);
        when(row.delegationKey()).thenReturn(delegationKey);
        when(row.unattended()).thenReturn(unattended);
        return row;
    }
}
