package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 실행 시작과 종료가 하나의 기록을 상태 전이하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionLifecycleTest {

    private static final Long USER_ID = 4_102L;

    @Autowired ExecutionRecorder recorder;
    @Autowired AgentExecutionRepository executions;
    @Autowired ConversationRepository conversations;

    private Conversation conversation;

    @BeforeEach
    void 준비한다() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "실행", null));
    }

    @Test
    void 시작한_실행은_종료_정보_없이_RUNNING이고_부모와_뿌리를_저장한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), 12L, 3L, 0L);

        assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.finishedAt()).isNull();
        assertThat(execution.latencyMs()).isNull();
        assertThat(execution.parentExecutionId()).isEqualTo(12L);
        assertThat(execution.rootExecutionId()).isEqualTo(3L);
    }

    @Test
    void 완료는_같은_줄에_토큰과_금액을_갱신한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null, 0L);
        Long id = execution.id();

        AgentExecution completed = recorder.complete(execution, agent(), result());

        assertThat(completed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(saved.inputTokens()).isEqualTo(120L);
            assertThat(saved.estimatedCostMicros()).isNull();
            assertThat(saved.finishedAt()).isNotNull();
            assertThat(saved.latencyMs()).isNotNull();
        });
    }

    @Test
    void 실패와_run_번호_연결은_같은_줄을_갱신한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null, 0L);
        Long id = execution.id();

        recorder.attachRunId(execution, "run-1");
        AgentExecution failed = recorder.fail(execution, "HERMES_UNAVAILABLE");

        assertThat(failed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.hermesRunId()).isEqualTo("run-1");
            assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(saved.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }

    @Test
    void 실행_중인_줄은_가격_미확인_실행으로_세지_않는다() {
        recorder.start(user(), conversation, agent(), null, null, 0L);

        MonthlyCost cost = executions.sumCostBetween(
                USER_ID, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        assertThat(cost.totalMicros()).isZero();
        assertThat(cost.pricedExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isZero();
    }

    private static CurrentUser user() {
        return new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }

    private static Agent agent() {
        return Agent.of("dad", "Dad", "dad", "http://127.0.0.1:1/p/dad", "anthropic", "claude-opus-5",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, USER_ID);
    }

    private static HermesRunResult result() {
        return new HermesRunResult("run-1", "session-1", "completed", "끝", "claude-opus-5", "anthropic",
                new TokenUsage(120L, 80L, 40L, 160L));
    }
}
