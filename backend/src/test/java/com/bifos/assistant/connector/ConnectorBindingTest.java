package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorBindingTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-01-03T00:00:00Z");


    @Test
    @DisplayName("일반 에이전트의 바인딩은 상태가 바뀌어도 에이전트의 enabled 를 바꾸지 않는다")
    void ordinaryAgentEnabledIsUntouched() {
        Agent enabledAgent = agent();
        ConnectorBinding binding = ConnectorBinding.pending(enabledAgent, connection(), "demo", CREATED);

        binding.beginInstall(UUID.randomUUID(), LATER);
        binding.installed(true, LATER);
        binding.pending(LATER);

        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(enabledAgent.enabled()).as("PENDING 바인딩의 켜진 일반 에이전트").isTrue();

        Agent disabledAgent = agent();
        disabledAgent.changeAccess(false, disabledAgent.visibility(), disabledAgent.ownerUserId());
        ConnectorBinding other = ConnectorBinding.pending(disabledAgent, connection(), "demo", CREATED);

        other.ready(LATER);

        assertThat(other.status()).isEqualTo(BindingStatus.READY);
        assertThat(disabledAgent.enabled()).as("READY 바인딩의 꺼진 일반 에이전트").isFalse();
    }


    @Test
    @DisplayName("installed 는 재시작 대기를 누적하고 재시작이 필요한 설치의 시각만 대기 시작으로 적는다")
    void installedAccumulatesRestartRequired() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(), connection(), "demo", CREATED);
        assertThat(binding.restartRequired()).isFalse();
        assertThat(binding.restartRequiredSince()).isNull();
        assertThat(binding.desiredEnabled()).isFalse();

        binding.beginInstall(UUID.randomUUID(), LATER);
        binding.installed(true, LATER);

        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(binding.desiredEnabled()).isTrue();
        assertThat(binding.restartRequired()).isTrue();
        assertThat(binding.restartRequiredSince()).isEqualTo(LATER);

        binding.beginInstall(UUID.randomUUID(), LATEST);
        assertThat(binding.desiredEnabled()).as("설치를 시작하면 켜려는 의도를 내린다").isFalse();
        binding.installed(false, LATEST);

        assertThat(binding.restartRequired())
            .as("재시작이 필요 없는 설치가 앞선 대기를 지우지 않는다")
            .isTrue();
        assertThat(binding.restartRequiredSince())
            .as("재시작이 필요 없는 설치는 대기 시작 시각을 옮기지 않는다")
            .isEqualTo(LATER);

        binding.ready(LATEST);

        assertThat(binding.restartRequired()).as("반영되면 대기가 풀린다").isFalse();
    }


    @Test
    @DisplayName("재시작 대기 표시는 거짓이 와도 앞선 참을 지우지 않는다")
    void markRestartRequiredAccumulatesWithLogicalOr() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(), connection(), "demo", CREATED);

        binding.markRestartRequired(true);
        binding.markRestartRequired(false);

        assertThat(binding.restartRequired()).isTrue();
    }


    @Test
    @DisplayName("반영 예정 시각은 더 늦은 값을 지키고 clearApplyDue 와 ready 가 비운다")
    void scheduleApplyKeepsLaterDueAndReadyClearsIt() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(), connection(), "demo", CREATED);
        assertThat(binding.applyDueAt()).isNull();

        binding.scheduleApply(LATER);
        assertThat(binding.applyDueAt()).as("빈 예정에 적는다").isEqualTo(LATER);

        binding.scheduleApply(LATEST);
        assertThat(binding.applyDueAt()).as("더 늦은 예정으로 바꾼다").isEqualTo(LATEST);

        binding.scheduleApply(LATER);
        assertThat(binding.applyDueAt()).as("더 이른 예정이 늦은 예정을 당기지 않는다").isEqualTo(LATEST);

        binding.clearApplyDue();
        assertThat(binding.applyDueAt()).as("확인을 시작하면 비운다").isNull();

        binding.scheduleApply(LATER);
        binding.ready(LATEST);

        assertThat(binding.applyDueAt()).as("반영되면 예정이 풀린다").isNull();
        assertThat(binding.status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("서버 이름이 빈 옛 바인딩에 서버 이름을 적는다")
    void recordServerFillsServerName() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(), connection(), null, CREATED);
        assertThat(binding.mcpServer()).isNull();

        binding.recordServer("demo");

        assertThat(binding.mcpServer()).isEqualTo("demo");
    }


    private static Agent agent() {
        Agent agent = Agent.of(
            "demo",
            "검사용 메모",
            "demo-profile",
            "http://localhost",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            1L);
        return agent;
    }


    @Test
    @DisplayName("같은 시각에도 새 시도만 한 번 소비하고 이전 시도와 중복 결과는 버린다")
    void consumesCurrentInstallAttemptOnce() {
        ConnectorBinding binding = ConnectorBinding.pending(agent(), connection(), "demo", CREATED);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        binding.beginInstall(first, LATER);
        binding.beginInstall(second, LATER);
        assertThat(binding.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(binding.desiredEnabled()).isFalse();
        assertThat(binding.consumeInstall(first)).isFalse();
        assertThat(binding.installAttemptId()).isEqualTo(second);
        assertThat(binding.consumeInstall(second)).isTrue();
        assertThat(binding.consumeInstall(second)).isFalse();
        assertThat(binding.consumeInstall(null)).isFalse();
        assertThat(binding.installAttemptId()).isNull();
    }


    private static ConnectorConnection connection() {
        return ConnectorConnection.pending(1L, "demo-notes", CREATED);
    }
}
