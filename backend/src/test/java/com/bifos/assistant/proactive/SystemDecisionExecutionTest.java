package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.UsageSummaryService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SystemDecisionExecutionTest {

    private static final CurrentUser USER = new CurrentUser(960_001L, "user@example.com", "사용자A", 1L, UserRole.MEMBER);

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    UsageSummaryService usage;

    @Autowired
    Clock clock;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executions.deleteAllById(created);
    }

    @Test
    @DisplayName("시스템 판단도 요청자의 실행 기록과 합계에 남고 에이전트 없는 줄을 정렬할 수 있다")
    void recordsSystemExecutionWithoutCreatingAnAgent() {
        Instant now = clock.instant();
        AgentExecution system = recorder.startSystem(
                USER,
                "decision-test",
                CostMode.SUBSCRIPTION,
                ModelChoice.of("requested-provider", "requested-model", "high"));
        created.add(system.id());
        HermesRunResult result = new HermesRunResult(
                "system-run",
                "session-new",
                "completed",
                "not-stored",
                "requested-model",
                "requested-provider",
                null,
                TokenUsage.empty(),
                new SessionRuntime("actual-model", "actual-provider"));
        recorder.completeSystem(system, result, "http://hermes.example.com");
        assertThat(executions.findById(system.id()).orElseThrow()).satisfies(row -> {
            assertThat(row.agentId()).isNull();
            assertThat(row.conversationId()).isNull();
            assertThat(row.userId()).isEqualTo(USER.id());
            assertThat(row.provider()).isEqualTo("actual-provider");
            assertThat(row.model()).isEqualTo("actual-model");
            assertThat(row.outputText()).isNull();
        });
        AgentExecution other = executions.save(AgentExecution.builder()
                .userId(USER.id())
                .agentId(960_101L)
                .profileName("source-test")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(now)
                .build());
        created.add(other.id());
        var lines =
                usage.byAgent(USER.id(), now.minusSeconds(1), clock.instant().plusSeconds(1));
        assertThat(lines).hasSize(2);
        assertThat(lines.getLast().cost().agentId()).isNull();
    }

    @Test
    @DisplayName("실패한 판단도 실제 모델과 토큰은 보존하고 오류 본문은 저장하지 않는다")
    void preservesUsageOfFailedSystemExecution() {
        AgentExecution system = recorder.startSystem(USER, "decision-test", CostMode.SUBSCRIPTION,
                ModelChoice.of("requested-provider", "requested-model", null));
        created.add(system.id());
        HermesRunResult result = new HermesRunResult("failed-run", "session-new", "failed", null,
                null, null, "private error", new TokenUsage(10L, 0L, 4L, 14L),
                new SessionRuntime("actual-model", "actual-provider"));
        recorder.failSystem(system, result, "http://hermes.example.com", "DECISION_PROVIDER_FAILED");
        assertThat(executions.findById(system.id()).orElseThrow()).satisfies(row -> {
            assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(row.provider()).isEqualTo("actual-provider");
            assertThat(row.model()).isEqualTo("actual-model");
            assertThat(row.inputTokens()).isEqualTo(10L);
            assertThat(row.outputTokens()).isEqualTo(4L);
            assertThat(row.outputText()).isNull();
            assertThat(row.errorCode()).isEqualTo("DECISION_PROVIDER_FAILED");
        });
    }
}
