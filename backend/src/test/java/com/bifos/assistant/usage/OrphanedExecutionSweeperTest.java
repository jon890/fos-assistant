package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.usage.application.OrphanedExecutionSweeper;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 기동 시 남아 있는 실행 기록만 실패로 정리하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class OrphanedExecutionSweeperTest {

    @Autowired OrphanedExecutionSweeper sweeper;
    @Autowired AgentExecutionRepository executions;

    @BeforeEach
    void 준비한다() {
        executions.deleteAll();
    }

    @Test
    void 실행_중인_두_줄을_고아_실패로_정리한다() {
        AgentExecution first = executions.save(running(1L));
        AgentExecution second = executions.save(running(2L));

        sweeper.sweep();

        assertOrphaned(first.id());
        assertOrphaned(second.id());
    }

    @Test
    void 성공한_줄은_건드리지_않는다() {
        AgentExecution succeeded = executions.save(succeeded(1L));

        sweeper.sweep();

        AgentExecution saved = executions.findById(succeeded.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.errorCode()).isNull();
    }

    @Test
    void 실행_중인_줄이_없으면_아무것도_바꾸지_않는다() {
        AgentExecution succeeded = executions.save(succeeded(1L));

        sweeper.sweep();

        assertThat(executions.findById(succeeded.id()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(executions.count()).isOne();
    }

    private void assertOrphaned(Long id) {
        AgentExecution saved = executions.findById(id).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("ORPHANED");
        assertThat(saved.finishedAt()).isNotNull();
        assertThat(saved.latencyMs()).isNotNull();
    }

    private static AgentExecution running(Long conversationId) {
        return base(conversationId).status(ExecutionStatus.RUNNING).build();
    }

    private static AgentExecution succeeded(Long conversationId) {
        Instant startedAt = Instant.now().minusSeconds(1);
        return base(conversationId)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(startedAt, Instant.now())
                .build();
    }

    private static AgentExecution.Builder base(Long conversationId) {
        return AgentExecution.builder()
                .userId(1L)
                .conversationId(conversationId)
                .profileName("test")
                .costMode(CostMode.SUBSCRIPTION)
                .startedAt(Instant.now().minusSeconds(1));
    }
}
