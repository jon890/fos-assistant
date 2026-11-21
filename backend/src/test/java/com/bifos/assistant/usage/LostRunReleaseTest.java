package com.bifos.assistant.usage;

import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.testsupport.TestAgentRuntimes;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.runtime.application.dto.RunCommand;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.LostRunRelease;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 새 실행을 보내기 전에 Hermes 가 잃었을 수 있는 지난 실행만 넘기는지 본다(docs/features/hermes-upgrade/design.md). */
@BackendIntegrationTest
class LostRunReleaseTest {

    private static final String PROFILE = "lost-run-dad";
    private static final Long USER_ID = 4_501L;

    @Autowired
    LostRunRelease release;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    JdbcTemplate jdbc;

    private Agent agent;


    @BeforeEach
    void setUp() {
        executions.deleteAll();
        stub().reset();
        agent = agents.findByCode(PROFILE)
            .orElseGet(() -> agents.save(Agent.of(
                PROFILE,
                "잃은 실행 아빠",
                PROFILE,
                "http://127.0.0.1:1/p/dad",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID)));
    }


    @Test
    @DisplayName("같은 profile 에서 24시간 안에 연결 실패나 시간 초과로 끝난 실행만 최근 순으로 넘긴다")
    void passesOnlyRecentLostCandidatesOfTheProfile() {
        Instant now = Instant.now();
        failed(PROFILE, "run-old-unavailable", ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofHours(25)));
        failed(PROFILE, "run-timeout", ErrorCode.HERMES_RUN_TIMEOUT.name(), now.minus(Duration.ofMinutes(30)));
        failed(PROFILE, "run-unreachable", "RECONCILE_UNREACHABLE", now.minus(Duration.ofMinutes(10)));
        failed(PROFILE, "run-provider", "PROVIDER_BLOCKED", now.minus(Duration.ofMinutes(5)));
        failed("other-profile", "run-other", ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofMinutes(5)));
        failed(PROFILE, null, ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofMinutes(5)));

        release.beforeSubmit(command());

        assertThat(stub().releasedRuns()).containsExactly("run-unreachable", "run-timeout");
    }


    @Test
    @DisplayName("후보가 많으면 최근 10개만 넘긴다")
    void passesAtMostTen() {
        Instant now = Instant.now();
        for (int i = 0; i < 12; i++) {
            failed(PROFILE, "run-" + i, ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofMinutes(60 - i)));
        }

        release.beforeSubmit(command());

        assertThat(stub().releasedRuns()).hasSize(10).first().isEqualTo("run-11");
    }


    @Test
    @DisplayName("Hermes 에 닿지 못하면 남은 후보를 건너뛰고 제출을 막지 않는다")
    void stopsAfterFirstFailureWithoutThrowing() {
        Instant now = Instant.now();
        failed(PROFILE, "run-a", ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofMinutes(2)));
        failed(PROFILE, "run-b", ErrorCode.HERMES_UNAVAILABLE.name(), now.minus(Duration.ofMinutes(3)));
        stub().failReleases(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        release.beforeSubmit(command());

        assertThat(stub().releasedRuns()).containsExactly("run-a");
    }


    private RunCommand command() {
        return new RunCommand(
            new RuntimeTarget(RuntimeKind.HERMES, "http://127.0.0.1:1/p/dad", PROFILE), "안녕", null, null, null, null, null);
    }


    private void failed(String profileName, String runId, String errorCode, Instant finishedAt) {
        AgentExecution saved = executions.save(AgentExecution.builder()
            .userId(USER_ID)
            .agentId(agent.id())
            .profileName(profileName)
            .hermesRunId(runId)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.FAILED)
            .errorCode(errorCode)
            .startedAt(finishedAt.minusSeconds(5))
            .build());
        // 빌더는 끝난 시각을 받지 않는다. 고르는 기간을 보려고 직접 적는다.
        jdbc.update("UPDATE agent_execution SET finished_at = ? WHERE id = ?", Timestamp.from(finishedAt), saved.id());
    }


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }
}
