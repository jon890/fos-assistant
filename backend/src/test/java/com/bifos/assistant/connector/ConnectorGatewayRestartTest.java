package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorBindingApplier;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.ConnectorGatewayRestarts;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.application.port.HermesGatewayClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공유 gateway 재시작을 기동 시각으로 알아채 재시작 대기 바인딩을 사람 없이 반영 맞추는 흐름을 본다(ADR-20261120 / connector-auto-apply).
 *
 * <p>대시보드와 기동 시각은 대역이고 시각은 {@link TestClock} 을 앞으로 돌린다. 재시작 대기는 {@code plugin_updated} 답으로 만든다.
 */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ConnectorGatewayRestartTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final Map<String, String> VALUES = Map.of("token", "demo_ok_0123456789");
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
        DEMO,
        "검사용 메모",
        "",
        List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null)),
        "list_scopes",
        "demo",
        2,
        List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)),
        List.of());
    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final String UNKNOWN_LOG = "shared gateway start time is unknown";
    private static final String READABLE_LOG = "shared gateway start time is readable again";
    /** {@code fos-ctx} 가 바뀌어 공유 gateway 재시작이 필요한 답이다. */
    private static final InstallResult PLUGIN_UPDATED = new InstallResult(false, true, false);
    private static final InstallResult UNCHANGED = new InstallResult(false, false, false);

    @Autowired
    ConnectorBindingService service;

    @Autowired
    ConnectorBindingApplier applier;

    @Autowired
    ConnectorGatewayRestarts restarts;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesGatewayClient gateway;

    @Autowired
    TestClock clock;

    private CurrentUser owner;


    @BeforeEach
    void setUp() {
        clock.set(T0);
        // 공유 컨텍스트의 다른 테스트가 남긴 로그 상태를 처음 값으로 되돌린다.
        ReflectionTestUtils.setField(restarts, "gatewayKnown", true);
        when(gateway.startedAt()).thenReturn(Optional.empty());
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.callWithVault(anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        stubInstall(PLUGIN_UPDATED);
        owner = user(UserRole.MEMBER);
        connectionService.register(owner, DEMO, VALUES);
    }


    @AfterEach
    void tearDown() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        clock.reset();
    }


    @Test
    @DisplayName("gateway 가 대기 뒤에 다시 뜨면 그 전에 대기가 된 바인딩만 반영 맞추기를 거쳐 READY 가 되고 뒤의 바인딩은 대기로 남는다")
    void restartedGatewayAppliesOnlyEarlierWaits() {
        Agent before = bindWaiting();
        clock.advance(Duration.ofMinutes(5));
        Instant startedAt = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        Agent after = bindWaiting();
        when(gateway.startedAt()).thenReturn(Optional.of(startedAt));
        stubInstall(UNCHANGED);
        clearInvocations(connector);

        applier.runScheduled();

        assertThat(bindingOf(before).status()).isEqualTo(BindingStatus.READY);
        assertThat(bindingOf(before).restartRequired()).isFalse();
        ConnectorBinding late = bindingOf(after);
        assertThat(late.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(late.restartRequired()).isTrue();
        verify(connector, times(1)).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    }


    @Test
    @DisplayName("기동 시각이 대기 시각보다 2초 안으로만 늦으면 재시작으로 보지 않는다")
    void startWithinSlackIsNotARestart() {
        Agent agent = bindWaiting();
        Instant since = bindingOf(agent).restartRequiredSince();
        when(gateway.startedAt()).thenReturn(Optional.of(since.plusSeconds(2)));
        stubInstall(UNCHANGED);

        applier.runScheduled();

        assertThat(bindingOf(agent).restartRequired()).isTrue();

        when(gateway.startedAt()).thenReturn(Optional.of(since.plusSeconds(3)));
        applier.runScheduled();

        assertThat(bindingOf(agent).status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("재시작 대기 시각은 설치의 결과 트랜잭션 시각이다")
    void restartSinceIsResultTransactionTime() {
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(10));
            return PLUGIN_UPDATED;
        }).when(connector).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        Agent agent = bindWaiting();

        assertThat(bindingOf(agent).restartRequiredSince()).isEqualTo(T0.plusSeconds(10));
    }


    @Test
    @DisplayName("기동 시각을 모르면 대기로 남고 경고가 바뀔 때만 한 줄 남으며, 다시 읽히면 info 가 한 줄 남는다")
    void unknownStartKeepsWaitingAndLogsOnChange(CapturedOutput output) {
        Agent agent = bindWaiting();

        applier.runScheduled();
        applier.runScheduled();

        assertThat(bindingOf(agent).restartRequired()).isTrue();
        assertThat(bindingOf(agent).status()).isEqualTo(BindingStatus.PENDING);
        assertThat(count(output.getAll(), UNKNOWN_LOG)).isEqualTo(1);

        when(gateway.startedAt()).thenReturn(Optional.of(T0.minusSeconds(60)));
        applier.runScheduled();
        applier.runScheduled();

        assertThat(count(output.getAll(), READABLE_LOG)).isEqualTo(1);
        assertThat(bindingOf(agent).restartRequired()).as("대기 전에 뜬 gateway 다").isTrue();
    }


    @Test
    @DisplayName("재시작 대기 바인딩이 없으면 기동 시각을 읽지 않는다")
    void noWaitingBindingSkipsGatewayCall() {
        applier.runScheduled();

        verify(gateway, never()).startedAt();
    }


    @Test
    @DisplayName("실패 표시가 있는 재시작 대기 바인딩은 gateway 가 다시 떠도 집지 않는다")
    void failedBindingIsNotPicked() {
        Agent agent = bindWaiting();
        jdbc.update("UPDATE agent_connector_binding SET apply_failed_at = ? WHERE id = ?",
            Timestamp.from(T0), bindingOf(agent).id());
        clearInvocations(connector);
        when(gateway.startedAt()).thenReturn(Optional.of(T0.plus(Duration.ofHours(1))));

        applier.runScheduled();

        verify(gateway, never()).startedAt();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(bindingOf(agent).restartRequired()).isTrue();
    }


    @Test
    @DisplayName("재시작 감지가 다시 보낸 설치의 probe 가 실패하면 실패 표시를 남기고 다음 주기에 다시 집지 않는다")
    void probeFailureAfterRestartIsMarked() {
        Agent agent = bindWaiting();
        clock.advance(Duration.ofMinutes(1));
        when(gateway.startedAt()).thenReturn(Optional.of(clock.instant()));
        stubInstall(UNCHANGED);
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));

        applier.runScheduled();

        ConnectorBinding failed = bindingOf(agent);
        assertThat(failed.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(failed.applyFailedAt()).isNotNull();

        clearInvocations(connector);
        applier.runScheduled();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    }


    @Test
    @DisplayName("반영 예정 시각이 남은 재시작 대기 바인딩은 그 시각이 지난 뒤 재시작 감지가 맡는다")
    void restartWaitWithApplyDueIsPickedAfterDue() {
        Agent agent = bindWaiting();
        // 꺼진 동안 plugin_updated 를 받고 켠 바인딩은 재시작 대기와 반영 예정 시각을 함께 갖는다.
        jdbc.update("UPDATE agent_connector_binding SET apply_due_at = ? WHERE id = ?",
            Timestamp.from(T0.plus(Duration.ofMinutes(3))), bindingOf(agent).id());
        when(gateway.startedAt()).thenReturn(Optional.of(T0.plus(Duration.ofMinutes(1))));
        stubInstall(UNCHANGED);
        clock.advance(Duration.ofMinutes(2));

        applier.runScheduled();
        assertThat(bindingOf(agent).restartRequired()).as("예정 시각 전이다").isTrue();

        clock.advance(Duration.ofMinutes(2));
        applier.runScheduled();
        assertThat(bindingOf(agent).status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("에이전트가 지워진 재시작 대기 바인딩은 gateway 가 다시 떠도 설치를 보내지 않는다")
    void deletedAgentIsSkipped() {
        Agent agent = bindWaiting();
        jdbc.update("UPDATE agent SET deleted_at = ? WHERE id = ?", Timestamp.from(T0), agent.id());
        clearInvocations(connector);
        when(gateway.startedAt()).thenReturn(Optional.of(T0.plus(Duration.ofMinutes(1))));

        applier.runScheduled();

        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(bindingOf(agent).restartRequired()).isTrue();
    }


    @Test
    @DisplayName("재시작 대기 줄의 다시 확인은 그 대기 뒤에 gateway 가 뜨지 않았으면 아무것도 바꾸지 않고 거절한다")
    void confirmRejectsWithoutRestart() {
        CurrentUser admin = user(UserRole.ADMIN);
        Agent agent = bindWaiting();
        ConnectorBinding waiting = bindingOf(agent);
        Instant since = waiting.restartRequiredSince();
        clearInvocations(connector);
        stubInstall(UNCHANGED);

        for (Instant startedAt : List.of(since.minusSeconds(60), since.plusSeconds(2))) {
            when(gateway.startedAt()).thenReturn(Optional.of(startedAt));
            assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, since))
                .isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_RESTART_NOT_DONE));
        }

        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        ConnectorBinding after = bindingOf(agent);
        assertThat(after.restartRequired()).isTrue();
        assertThat(after.updatedAt()).isEqualTo(waiting.updatedAt());
    }


    @Test
    @DisplayName("재시작 대기 줄의 다시 확인은 대기 뒤에 gateway 가 떴거나 기동 시각을 모르면 지금처럼 다시 맞춰 READY 가 된다")
    void confirmProceedsAfterRestartOrWhenUnknown() {
        CurrentUser admin = user(UserRole.ADMIN);
        Agent restarted = bindWaiting();
        Agent unknown = bindWaiting();
        stubInstall(UNCHANGED);
        clock.advance(Duration.ofMinutes(1));

        when(gateway.startedAt()).thenReturn(Optional.of(clock.instant()));
        service.confirmApplied(admin, restarted.code(), DEMO, bindingOf(restarted).restartRequiredSince());
        when(gateway.startedAt()).thenReturn(Optional.empty());
        service.confirmApplied(admin, unknown.code(), DEMO, bindingOf(unknown).restartRequiredSince());

        assertThat(bindingOf(restarted).status()).isEqualTo(BindingStatus.READY);
        assertThat(bindingOf(unknown).status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("재시작 감지가 실패 표시를 남긴 바인딩은 대기 뒤에 gateway 가 떴으면 다시 확인이 READY 로 만들고 표시를 비운다")
    void confirmClearsFailureAfterRestart() {
        CurrentUser admin = user(UserRole.ADMIN);
        Agent agent = bindWaiting();
        clock.advance(Duration.ofMinutes(1));
        when(gateway.startedAt()).thenReturn(Optional.of(clock.instant()));
        stubInstall(UNCHANGED);
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));
        applier.runScheduled();
        assertThat(bindingOf(agent).applyFailedAt()).isNotNull();
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        service.confirmApplied(admin, agent.code(), DEMO, bindingOf(agent).restartRequiredSince());

        ConnectorBinding ready = bindingOf(agent);
        assertThat(ready.status()).isEqualTo(BindingStatus.READY);
        assertThat(ready.applyFailedAt()).isNull();
    }


    @Test
    @DisplayName("껐다 켜기 단계의 바인딩은 재시작 대기여도 기동 시각으로 거절하지 않고 그 단계의 다음 설치를 보낸다")
    void confirmInCycleIgnoresGatewayStart() {
        CurrentUser admin = user(UserRole.ADMIN);
        Agent agent = bindWaiting();
        ConnectorBinding waiting = bindingOf(agent);
        jdbc.update("UPDATE agent_connector_binding SET cycle_phase = 'OFF', cycle_at = ? WHERE id = ?",
            Timestamp.from(T0), waiting.id());
        when(gateway.startedAt()).thenReturn(Optional.of(T0.minus(Duration.ofHours(1))));
        clearInvocations(connector);
        stubInstall(UNCHANGED);

        assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, waiting.restartRequiredSince()))
            .isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_APPLY_SCHEDULED));
        verify(connector, times(1)).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    }


    @Test
    @DisplayName("기동 시각보다 앞선 대기가 없으면 카탈로그를 읽지 않는다")
    void noEarlierWaitSkipsCatalog() {
        Agent agent = bindWaiting();
        clearInvocations(connector);
        when(gateway.startedAt()).thenReturn(Optional.of(T0.minusSeconds(60)));

        restarts.detect();

        verify(gateway, times(1)).startedAt();
        verify(connector, never()).readCatalog();
        assertThat(bindingOf(agent).restartRequired()).isTrue();
    }


    @Test
    @DisplayName("카탈로그를 읽지 못한 주기는 실패 표시 없이 건너뛰고 다음 주기에 반영 맞춘다")
    void catalogFailureSkipsCycle() {
        Agent agent = bindWaiting();
        clock.advance(Duration.ofMinutes(1));
        when(gateway.startedAt()).thenReturn(Optional.of(clock.instant()));
        stubInstall(UNCHANGED);
        doThrow(new IllegalStateException()).when(connector).readCatalog();

        restarts.detect();

        ConnectorBinding skipped = bindingOf(agent);
        assertThat(skipped.restartRequired()).isTrue();
        assertThat(skipped.applyFailedAt()).isNull();

        doReturn(List.of(DEMO_MANIFEST)).when(connector).readCatalog();
        restarts.detect();
        assertThat(bindingOf(agent).status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("대기 시각이 빈 옛 재시작 대기 바인딩만 있으면 기동 시각을 읽지 않는다")
    void legacyWaitWithoutSinceIsNotATarget() {
        Agent agent = bindWaiting();
        jdbc.update("UPDATE agent_connector_binding SET restart_required_since = NULL WHERE id = ?", bindingOf(agent).id());

        applier.runScheduled();

        verify(gateway, never()).startedAt();
    }


    /** 새 에이전트에 붙여 재시작 대기 바인딩을 만든다. 답은 그때의 대역 설치가 정한다. */
    private Agent bindWaiting() {
        Agent agent = agent(owner);
        service.bind(owner, agent.code(), DEMO);
        assertThat(bindingOf(agent).restartRequired()).isTrue();
        return agent;
    }


    private void stubInstall(InstallResult result) {
        doReturn(result)
            .when(connector).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    }


    private ConnectorBinding bindingOf(Agent agent) {
        List<ConnectorBinding> found = bindings.findByAgentId(agent.id());
        assertThat(found).hasSize(1);
        return found.get(0);
    }


    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }


    private Agent agent(CurrentUser user) {
        String code = "restart-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
            code,
            code,
            "profile-" + code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            user.id()));
    }


    private CurrentUser user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("restart-" + suffix + "@example.com", suffix, 1L, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
