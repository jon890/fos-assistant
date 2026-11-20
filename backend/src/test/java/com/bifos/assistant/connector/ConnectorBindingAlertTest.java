package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorBindingAlerts;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
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
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자동 반영이 실패했거나 대기가 24시간 넘게 남은 바인딩에만 그룹 관리자 알림이 한 번 가는지 본다(ADR-20261120 / connector-auto-apply,
 * 스펙 REQ8).
 *
 * <p>재시작 대기는 {@code plugin_updated} 답으로 만들고 시각은 {@link TestClock} 을 앞으로 돌린다. 기동 시각은 모르는 것으로 두어 재시작
 * 감지가 대기를 풀지 않는다.
 */
@BackendIntegrationTest
class ConnectorBindingAlertTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    /** 이 검사만 쓰는 그룹이다. 정리할 때 이 그룹의 사용자와 알림만 지운다. */
    private static final long GROUP = 7_302L;
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
    private static final Duration DAY = Duration.ofHours(24);
    private static final InstallResult PLUGIN_UPDATED = new InstallResult(false, true, false);

    @Autowired
    ConnectorBindingAlerts alerts;

    @Autowired
    ConnectorBindingService service;

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
    AllowedPersonRepository people;

    @Autowired
    NotificationRepository notificationRows;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesGatewayClient gateway;

    @Autowired
    TestClock clock;

    private CurrentUser owner;
    private AppUser admin;
    private AppUser revokedAdmin;
    private Agent agent;


    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(gateway.startedAt()).thenReturn(Optional.empty());
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        stubInstall(PLUGIN_UPDATED);
        owner = member();
        admin = user(UserRole.ADMIN);
        revokedAdmin = user(UserRole.ADMIN);
        transactions.executeWithoutResult(status -> {
            AllowedPerson person = people.save(
                AllowedPerson.of(revokedAdmin.email(), "사용자", "revoked-" + revokedAdmin.id()));
            person.disable();
            people.saveAndFlush(person);
        });
        agent = agent();
        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_0123456789"));
        service.bind(owner, agent.code(), DEMO);
        assertThat(binding().restartRequired()).as("재시작 대기").isTrue();
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM notification WHERE user_id IN (SELECT id FROM app_user WHERE group_id = ?)", GROUP);
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        jdbc.update("DELETE FROM allowed_person WHERE email IN (SELECT email FROM app_user WHERE group_id = ?)", GROUP);
        jdbc.update("DELETE FROM app_user WHERE group_id = ?", GROUP);
        clock.reset();
    }


    @Test
    @DisplayName("재시작 대기는 24시간이 되기 전에는 알리지 않고, 넘으면 차단되지 않은 관리자에게만 한 번 알린다")
    void notifiesOnceAfterDay() {
        // 설치 revision 이 같은 시계 값에서 1 마이크로초 앞서 나갈 수 있다.
        assertThat(binding().waitingSince()).isBetween(T0, T0.plusMillis(1));

        clock.set(T0.plus(DAY).minusSeconds(1));
        assertThat(alerts.notifyDue()).as("24시간 직전").isZero();
        assertThat(notificationsOf(admin.id())).isEmpty();

        clock.set(T0.plus(DAY).plusSeconds(1));
        assertThat(alerts.notifyDue()).as("24시간").isEqualTo(1);
        assertThat(alerts.notifyDue()).as("같은 대기에 다시").isZero();

        List<Notification> sent = notificationsOf(admin.id());
        assertThat(sent).hasSize(1);
        Notification notice = sent.get(0);
        assertThat(notice.kind()).isEqualTo(NotificationKind.CONNECTOR_REINSTALLED);
        assertThat(notice.title()).isEqualTo("연결 반영을 확인해 주세요");
        assertThat(notice.body()).contains(agent.code()).contains(DEMO).contains("하루 넘게").contains("「다시 확인」");
        assertThat(notice.targetType()).isEqualTo(NotificationTargetType.ADMIN_CONNECTIONS);
        assertThat(notificationsOf(revokedAdmin.id())).as("차단된 관리자").isEmpty();
        assertThat(notificationsOf(owner.id())).as("MEMBER 역할 사용자").isEmpty();
        assertThat(binding().adminNotifiedAt()).isEqualTo(T0.plus(DAY).plusSeconds(1));
    }


    @Test
    @DisplayName("자동 반영이 실패한 바인딩은 24시간을 기다리지 않고 바로 한 번 알린다")
    void notifiesFailureAtOnce() {
        markFailed();

        assertThat(alerts.notifyDue()).isEqualTo(1);
        assertThat(alerts.notifyDue()).isZero();

        List<Notification> sent = notificationsOf(admin.id());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body()).contains("자동으로 반영하지 못했어요");
    }


    @Test
    @DisplayName("사람이 다시 설치하면 대기 시각과 알림 기록을 비워 새 대기의 24시간을 처음부터 센다")
    void humanReinstallRestartsCount() {
        clock.set(T0.plus(DAY).plusSeconds(1));
        assertThat(alerts.notifyDue()).isEqualTo(1);
        clock.set(T0.plus(DAY).plus(Duration.ofHours(1)));

        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_new_0123456789"));

        ConnectorBinding again = binding();
        assertThat(again.restartRequired()).as("여전히 재시작 대기").isTrue();
        assertThat(again.adminNotifiedAt()).isNull();
        assertThat(again.waitingSince()).isBetween(T0.plus(DAY).plus(Duration.ofHours(1)), T0.plus(DAY).plus(Duration.ofHours(1)).plusMillis(1));
        clock.set(T0.plus(DAY).plus(DAY));
        assertThat(alerts.notifyDue()).as("새 대기는 아직 24시간이 아니다").isZero();
        clock.set(T0.plus(DAY).plus(DAY).plus(Duration.ofHours(1)));
        assertThat(alerts.notifyDue()).isEqualTo(1);
        assertThat(notificationsOf(admin.id())).hasSize(2);
    }


    @Test
    @DisplayName("READY 가 되면 대기 시각과 알림 기록이 비고 알리지 않는다")
    void readyClearsWaiting() {
        transactions.executeWithoutResult(status -> {
            ConnectorBinding found = bindings.findById(binding().id()).orElseThrow();
            found.ready(clock.instant());
            bindings.save(found);
        });
        clock.set(T0.plus(DAY).plus(DAY));

        assertThat(alerts.notifyDue()).isZero();
        assertThat(binding().waitingSince()).isNull();
        assertThat(binding().adminNotifiedAt()).isNull();
    }


    @Test
    @DisplayName("자동 반영이 실패한 바인딩을 관리자가 다시 확인했는데 또 실패하면 실패 표시가 남아 사용자 화면이 실패를 보인다")
    void failedRecheckKeepsFailure() {
        markFailed();
        Instant since = binding().restartRequiredSince();
        doReturn(new InstallResult(false, false, false))
            .when(connector).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));

        assertThatThrownBy(() -> service.confirmApplied(adminUser(), agent.code(), DEMO, since))
            .isInstanceOf(ApiException.class);

        ConnectorBinding failed = binding();
        assertThat(failed.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(failed.applyFailedAt()).as("다시 확인도 실패했다").isNotNull();
        assertThat(service.listForAgent(owner, agent.code()).connections())
            .filteredOn(view -> view.connectorId().equals(DEMO))
            .singleElement()
            .satisfies(view -> assertThat(view.applyFailed()).isTrue());
    }


    @Test
    @DisplayName("시작한 설치의 결과를 아직 적지 않은 바인딩은 다시 확인을 거절하고, 시도가 오래 남았으면 받는다")
    void recheckWaitsForInstallInFlight() {
        Instant since = binding().restartRequiredSince();
        jdbc.update("UPDATE agent_connector_binding SET install_attempt_id = ?, updated_at = ? WHERE id = ?",
            uuidBytes(UUID.randomUUID()), Timestamp.from(clock.instant()), binding().id());
        assertThat(binding().installInFlight(clock.instant())).isTrue();

        assertThatThrownBy(() -> service.confirmApplied(adminUser(), agent.code(), DEMO, since))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.CONNECTOR_APPLY_SCHEDULED);
        assertThat(binding().installInFlight(clock.instant().plus(Duration.ofMinutes(11))))
            .as("결과 저장이 실패해 남은 시도는 영영 막지 않는다").isFalse();
    }


    private CurrentUser adminUser() {
        return new CurrentUser(admin.id(), admin.email(), admin.displayName(), admin.groupId(), admin.role());
    }


    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }


    private void markFailed() {
        transactions.executeWithoutResult(status -> {
            ConnectorBinding found = bindings.findById(binding().id()).orElseThrow();
            found.markApplyFailed(clock.instant());
            bindings.save(found);
        });
    }


    private void stubInstall(InstallResult result) {
        doReturn(result)
            .when(connector).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
    }


    private ConnectorBinding binding() {
        List<ConnectorBinding> found = bindings.findByAgentId(agent.id());
        assertThat(found).hasSize(1);
        return found.get(0);
    }


    private List<Notification> notificationsOf(Long userId) {
        return notificationRows.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10));
    }


    private Agent agent() {
        String code = "alert-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
            code,
            code,
            "profile-" + code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
    }


    private CurrentUser member() {
        AppUser saved = user(UserRole.MEMBER);
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }


    private AppUser user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        return users.save(AppUser.of("alert-" + suffix + "@example.com", suffix, GROUP, role));
    }
}
