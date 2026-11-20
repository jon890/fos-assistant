package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
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
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.CyclePhase;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorField;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.ConnectorInstallConflict;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정의나 값이 바뀐 바인딩을 그 서버만 껐다 켜서 사람 없이 반영하는 흐름을 본다(ADR-20261120 / connector-auto-apply).
 *
 * <p>대시보드는 대역이고 시각은 {@link TestClock} 을 앞으로 돌린다. 주인이 바쁜지는 spy 빈 {@link UserExecutionLimiter} 의
 * {@code used} 를 바꿔 정한다.
 */
@BackendIntegrationTest
class ConnectorBindingCycleTest {
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
    private static final Duration APPLY_DELAY = Duration.ofSeconds(150);
    /** 저장한 시각은 시도 revision 때문에 마이크로초가 붙는다. 경계를 넘기려고 1초를 더한다. */
    private static final Duration PAST = Duration.ofSeconds(1);
    /** 재시작이 필요하지만 그 서버만 껐다 켜서 풀 수 있는 답이다. */
    private static final InstallResult NEEDS_CYCLE = new InstallResult(true, false, false, true, false);
    /** 그 서버가 꺼진 채 남았다는 답이다. */
    private static final InstallResult CYCLED_OFF = new InstallResult(false, false, false, false, true);
    private static final InstallResult RELOAD = new InstallResult(false, false, true);
    private static final InstallResult UNCHANGED = new InstallResult(false, false, false);

    @Autowired
    ConnectorBindingService service;

    @Autowired
    ConnectorBindingApplier applier;

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
    UserExecutionLimiter limiter;

    @Autowired
    TestClock clock;

    private CurrentUser owner;
    private Agent agent;


    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.callWithVault(anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        stubInstall(RELOAD);
        stubCycleOff(CYCLED_OFF);
        doReturn(0).when(limiter).used(anyLong());
        owner = user(UserRole.MEMBER);
        connectionService.register(owner, DEMO, VALUES);
        agent = agent(owner);
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM notification");
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        clock.reset();
    }


    @Test
    @DisplayName("껐다 켜기가 필요한 답이면 재시작 대기가 아니라 IDLE_WAIT 이고, 끄고 반영 예정 뒤 켜서 사람 없이 READY 가 된다")
    void cyclesWithoutRestartOrAdmin() {
        bindNeedingCycle();
        ConnectorBinding waiting = onlyBinding();
        assertThat(waiting.restartRequired()).as("재시작 대기가 아니다").isFalse();
        assertThat(waiting.cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);

        applier.runScheduled();
        verify(connector, times(1)).bindConnector(
            eq(agent.hermesProfile()), eq(DEMO), anyString(), anyString(), nullable(String.class),
            nullable(JsonNode.class), eq(true));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.OFF);

        clearInvocations(connector);
        clock.advance(APPLY_DELAY.minusSeconds(10));
        applier.runScheduled();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        clock.advance(Duration.ofSeconds(10).plus(PAST));
        applier.runScheduled();
        verify(connector, times(1)).bindConnector(anyString(), eq(DEMO), anyString(), anyString(), nullable(String.class));
        ConnectorBinding on = onlyBinding();
        assertThat(on.cyclePhase()).as("켰다").isNull();
        assertThat(on.applyDueAt()).isAfterOrEqualTo(T0.plus(APPLY_DELAY).plus(APPLY_DELAY));

        stubInstall(UNCHANGED);
        clock.advance(APPLY_DELAY.plus(PAST));
        applier.runScheduled();

        ConnectorBinding ready = onlyBinding();
        assertThat(ready.status()).isEqualTo(BindingStatus.READY);
        assertThat(ready.applyFailedAt()).isNull();
    }


    @Test
    @DisplayName("주인이 바쁘면 끄지 않고 기다리다가 10분이 지나면 끈다")
    void waitsForIdleOwnerUpToTenMinutes() {
        bindNeedingCycle();
        doReturn(1).when(limiter).used(anyLong());

        clock.advance(Duration.ofMinutes(9));
        applier.runScheduled();
        verify(connector, never()).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);

        clock.advance(Duration.ofMinutes(1).plus(PAST));
        applier.runScheduled();
        verify(connector, times(1)).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.OFF);
    }


    @Test
    @DisplayName("꺼진 동안 값을 다시 등록하면 끄기 설치로 새 값을 쓰고 단계와 꺼진 시각은 그대로다")
    void registerWhileOffKeepsItOff() {
        bindNeedingCycle();
        applier.runScheduled();
        Instant offAt = onlyBinding().cycleAt();
        clearInvocations(connector);
        clock.advance(Duration.ofSeconds(30));

        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_new_0123456789"));

        verify(connector, times(1)).bindConnector(
            eq(agent.hermesProfile()), eq(DEMO), anyString(), anyString(), nullable(String.class),
            nullable(JsonNode.class), eq(true));
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.OFF);
        assertThat(onlyBinding().cycleAt()).isEqualTo(offAt);
    }


    @Test
    @DisplayName("자동 끄기가 실패하면 실패 표시를 남기고 다시 보내지 않으며, 관리자 다시 확인이 표시를 비우고 끄기를 바로 보낸다")
    void failedCycleStopsUntilAdminConfirms() {
        bindNeedingCycle();
        stubCycleOffFailure(new IllegalStateException());

        applier.runScheduled();
        assertThat(onlyBinding().applyFailedAt()).isNotNull();
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);

        clearInvocations(connector);
        clock.advance(Duration.ofMinutes(5));
        applier.runScheduled();
        verify(connector, never()).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));

        stubCycleOff(CYCLED_OFF);
        CurrentUser admin = user(UserRole.ADMIN);
        assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, null))
            .isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_APPLY_SCHEDULED));
        verify(connector, times(1)).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));
        ConnectorBinding resumed = onlyBinding();
        assertThat(resumed.applyFailedAt()).isNull();
        assertThat(resumed.cyclePhase()).isEqualTo(CyclePhase.OFF);
    }


    @Test
    @DisplayName("끄기가 실패한 IDLE_WAIT 에 값을 다시 등록하면 기다림 상한을 지금부터 다시 세어 대화 중에는 끄지 않는다")
    void registerAfterFailedIdleWaitRestartsTheWait() {
        bindNeedingCycle();
        stubCycleOffFailure(new IllegalStateException());
        applier.runScheduled();
        assertThat(onlyBinding().applyFailedAt()).isNotNull();

        clock.advance(Duration.ofMinutes(30));
        stubInstall(NEEDS_CYCLE);
        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_new_0123456789"));
        ConnectorBinding resumed = onlyBinding();
        assertThat(resumed.applyFailedAt()).isNull();
        assertThat(resumed.cycleAt()).as("기다림을 지금부터 센다").isAfter(T0.plus(Duration.ofMinutes(29)));

        doReturn(1).when(limiter).used(anyLong());
        stubCycleOff(CYCLED_OFF);
        clearInvocations(connector);
        applier.runScheduled();

        verify(connector, never()).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);
    }


    @Test
    @DisplayName("자동 끄기가 409 로 거절돼도 예외로 끝나지 않고 실패 표시를 남긴다")
    void conflictOnCycleOffMarksFailure() {
        bindNeedingCycle();
        stubCycleOffFailure(new ConnectorInstallConflict());

        applier.runScheduled();

        assertThat(onlyBinding().applyFailedAt()).isNotNull();
    }


    @Test
    @DisplayName("연결 확인은 IDLE_WAIT 와 OFF 바인딩 모두에 설치를 보내지 않는다")
    void connectionCheckDoesNotInstallDuringCycle() {
        bindNeedingCycle();
        clearInvocations(connector);

        connectionService.check(owner, DEMO);

        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);

        applier.runScheduled();
        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.OFF);
        clearInvocations(connector);

        connectionService.check(owner, DEMO);

        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        verify(connector, never()).bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true));
        assertThat(onlyBinding().cyclePhase()).as("꺼진 채 남는다").isEqualTo(CyclePhase.OFF);
    }


    @Test
    @DisplayName("plugin_updated 답은 재시작 대기이고 IDLE_WAIT 를 비운다. cycle_available 이 없는 옛 답도 재시작 대기다")
    void pluginUpdateAndOldAnswersWaitForRestart() {
        bindNeedingCycle();
        stubInstall(new InstallResult(true, true));

        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_new_0123456789"));

        ConnectorBinding restart = onlyBinding();
        assertThat(restart.restartRequired()).isTrue();
        assertThat(restart.cyclePhase()).isNull();

        CurrentUser other = user(UserRole.MEMBER);
        connectionService.register(other, DEMO, VALUES);
        Agent otherAgent = agent(other);
        stubInstall(new InstallResult(true, false));
        service.bind(other, otherAgent.code(), DEMO);
        ConnectorBinding old = bindings.findByAgentId(otherAgent.id()).get(0);
        assertThat(old.restartRequired()).isTrue();
        assertThat(old.cyclePhase()).isNull();
    }


    @Test
    @DisplayName("반영 예정 확인의 probe 실패는 실패 표시를 남기고, 연결 확인의 성공이 비운다")
    void automaticProbeFailureIsMarkedAndCheckClearsIt() {
        service.bind(owner, agent.code(), DEMO);
        stubInstall(UNCHANGED);
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));
        clock.advance(APPLY_DELAY.plus(PAST));

        applier.runScheduled();
        assertThat(onlyBinding().applyFailedAt()).isNotNull();

        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        connectionService.check(owner, DEMO);

        ConnectorBinding checked = onlyBinding();
        assertThat(checked.status()).isEqualTo(BindingStatus.READY);
        assertThat(checked.applyFailedAt()).isNull();
    }


    @Test
    @DisplayName("정의 어긋남 점검이 다시 설치해 IDLE_WAIT 가 된 바인딩에는 관리자 알림이 생기지 않는다")
    void driftIntoCycleDoesNotNotifyAdmins() {
        CurrentUser admin = user(UserRole.ADMIN);
        service.bind(owner, agent.code(), DEMO);
        stubInstall(UNCHANGED);
        clock.advance(APPLY_DELAY.plus(PAST));
        applier.runScheduled();
        assertThat(onlyBinding().status()).isEqualTo(BindingStatus.READY);
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, false, false, true, HermesConnectorClient.MODE_BIND));
        stubInstall(NEEDS_CYCLE);

        applier.resyncDrifted();

        assertThat(onlyBinding().cyclePhase()).isEqualTo(CyclePhase.IDLE_WAIT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, admin.id()))
            .isZero();
    }


    private void bindNeedingCycle() {
        stubInstall(NEEDS_CYCLE);
        service.bind(owner, agent.code(), DEMO);
        stubInstall(RELOAD);
    }


    private void stubInstall(InstallResult result) {
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(result);
    }


    private void stubCycleOff(InstallResult result) {
        when(connector.bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true)))
                .thenReturn(result);
    }


    private void stubCycleOffFailure(RuntimeException failure) {
        when(connector.bindConnector(
            anyString(), anyString(), anyString(), anyString(), nullable(String.class), nullable(JsonNode.class), eq(true)))
                .thenThrow(failure);
    }


    private ConnectorBinding onlyBinding() {
        List<ConnectorBinding> all = bindings.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }


    private Agent agent(CurrentUser user) {
        String code = "cycle-" + UUID.randomUUID().toString().substring(0, 13);
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
        AppUser saved = users.save(AppUser.of("cycle-" + suffix + "@example.com", suffix, 1L, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
