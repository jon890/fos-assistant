package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.usage.execution.config.UserExecutionProperties;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 운영 재시작 조회가 쓰는 전체 진행 중 실행 수가 실행이 원격 종료 확인으로 넘어가는 순간에도 0 이 되지 않는지 본다(ADR-20261120 /
 * connector-auto-apply).
 */
class UserExecutionRunningTotalTest {

    @Test
    @DisplayName("RUNNING 줄을 읽는 사이 실행이 원격 종료 확인 자리로 넘어가도 그 실행을 센다")
    void countsExecutionMovingToRemoteEndHold() {
        AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
        UserExecutionLimiter limiter = new UserExecutionLimiter(
            LiveProperties.fixed(UserExecutionProperties.class, new UserExecutionProperties(3, 0, null)),
            executions,
            mock(HermesRunsClient.class),
            LiveProperties.fixed(
                HermesProperties.class, new HermesProperties(null, null, null, null, null, null, null, null)),
            new VirtualThreadBackgroundTasks());
        @SuppressWarnings("unchecked")
        Map<Long, Set<Long>> holds = (Map<Long, Set<Long>>)ReflectionTestUtils.getField(limiter, "remoteEndHolds");
        // 실행을 끝내는 쪽은 원격 종료 확인 자리를 먼저 쥐고 줄을 FAILED 로 바꾼다. 줄을 읽는 순간 그 전환이 끝났다.
        when(executions.findByStatus(ExecutionStatus.RUNNING)).thenAnswer(invocation -> {
            holds.put(1L, new HashSet<>(Set.of(10L)));
            return List.of();
        });

        assertThat(limiter.runningTotal()).isEqualTo(1);
    }
}
