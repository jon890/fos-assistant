package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorBindingApplier;
import com.bifos.assistant.connector.application.ConnectorBindingProperties;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ReadyBinding;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.SignInRevocation;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정의 어긋남 점검이 설치가 어긋난 {@code READY} 바인딩만 다시 맞추고 그룹 관리자에게 알리는지 본다(ADR-20261009 /
 * connector-install-drift).
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 점검이 바인딩마다 여는 트랜잭션과 주기 끝의 알림 트랜잭션이 실제로 돌아야 한다. 대시보드의
 * 커넥터 경로만 대역이다.
 */
@BackendIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ConnectorBindingDriftTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final String PLAIN = "demo-plain";
    /** 이 검사만 쓰는 그룹이다. 정리할 때 이 그룹의 사용자와 알림만 지운다. */
    private static final long GROUP = 7_301L;

    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "",
            List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null)),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)),
            List.of());
    private static final ConnectorManifest PLAIN_MANIFEST =
            new ConnectorManifest(PLAIN, "칸 없는 서버", "", List.of(), "ping", "plain", List.of(), false, 1, List.of());
    /** 반영 맞추기의 설치 판정을 통과하는 설치 상태다. */
    private static final ConnectorState INSTALLED =
            new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND);
    /** manifest 가 바뀌어 그 커넥터 항목만 {@code configured} 가 거짓인 설치 상태다. */
    private static final ConnectorState DRIFTED =
            new ConnectorState("p", true, false, false, true, HermesConnectorClient.MODE_BIND);

    private static final String RESTART_BODY = "공유 gateway 를 재시작한 뒤";
    private static final String CONFIRM_ONLY_BODY = "반영을 확인하지 못했어요";

    @Autowired
    ConnectorBindingApplier applier;

    @Autowired
    ConnectorBindingService service;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    NotificationRepository notificationRows;

    @Autowired
    NotificationService notifications;

    @Autowired
    SignInRevocation access;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser owner;
    private AppUser admin;
    private AppUser revokedAdmin;
    private Agent agent;
    private ConnectorBinding demo;
    private ConnectorBinding plain;

    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST, PLAIN_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(true, false));
        when(connector.readConnector(anyString(), anyString())).thenReturn(INSTALLED);
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        owner = member();
        admin = user(UserRole.ADMIN);
        revokedAdmin = user(UserRole.ADMIN);
        transactions.executeWithoutResult(status -> {
            AllowedPerson person = people.save(
                    AllowedPerson.of(revokedAdmin.email(), "사용자", "revoked-" + revokedAdmin.id(), Instant.now()));
            person.disable();
            people.saveAndFlush(person);
        });
        agent = agent(owner);
        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_0123456789"));
        connectionService.register(owner, PLAIN, Map.of());
        service.bind(owner, agent.code(), DEMO);
        service.bind(owner, agent.code(), PLAIN);
        // 붙이기는 재시작 대기로 끝난다. 관리자가 반영 완료를 마친 READY 바인딩으로 둔다.
        jdbc.update("UPDATE agent_connector_binding SET status = 'READY', restart_required = FALSE,"
                + " restart_required_since = NULL, apply_due_at = NULL WHERE agent_id = ?", agent.id());
        demo = binding(DEMO);
        plain = binding(PLAIN);
        clearInvocations(connector);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM notification WHERE user_id IN (SELECT id FROM app_user WHERE group_id = ?)", GROUP);
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        jdbc.update("DELETE FROM allowed_person WHERE email IN (SELECT email FROM app_user WHERE group_id = ?)", GROUP);
        jdbc.update("DELETE FROM app_user WHERE group_id = ?", GROUP);
    }

    @Test
    @DisplayName("같은 profile 의 READY 바인딩 가운데 설치가 어긋난 하나만 다시 설치하고 차단되지 않은 관리자에게만 재시작 안내를 알린다")
    void reinstallsOnlyDriftedBindingAndNotifiesActiveAdmins(CapturedOutput output) {
        when(connector.readConnector(agent.hermesProfile(), DEMO)).thenReturn(DRIFTED);

        assertThat(applier.resyncDrifted()).isEqualTo(1);

        verify(connector, times(1))
                .bindConnector(eq(agent.hermesProfile()), eq(DEMO), anyString(), anyString(), nullable(String.class));
        verify(connector, never())
                .bindConnector(anyString(), eq(PLAIN), anyString(), anyString(), nullable(String.class));
        ConnectorBinding drifted = reload(demo);
        assertThat(drifted.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(drifted.restartRequired()).as("재시작 대기").isTrue();
        assertThat(reload(plain).status()).isEqualTo(BindingStatus.READY);
        List<Notification> sent = notificationsOf(admin.id());
        assertThat(sent).hasSize(1);
        Notification notice = sent.get(0);
        assertThat(notice.kind()).isEqualTo(NotificationKind.CONNECTOR_REINSTALLED);
        assertThat(notice.title()).isEqualTo("연결 설치를 다시 맞췄어요");
        assertThat(notice.body()).contains("연결 1개").contains(RESTART_BODY);
        assertThat(notice.targetType()).isEqualTo(NotificationTargetType.ADMIN_CONNECTIONS);
        assertThat(notice.targetPublicId()).isNull();
        assertThat(notificationsOf(revokedAdmin.id())).as("차단된 관리자").isEmpty();
        assertThat(notificationsOf(owner.id())).as("MEMBER 역할 사용자").isEmpty();
        assertThat(output.getAll()).contains("connector " + DEMO + " drifted: NOT_INSTALLED");
    }

    @Test
    @DisplayName("다시 맞춘 바인딩은 READY 가 아니어서 다음 점검이 설치를 다시 보내지 않고 알림도 늘지 않는다")
    void doesNotReinstallAgainOnNextRun() {
        when(connector.readConnector(agent.hermesProfile(), DEMO)).thenReturn(DRIFTED);
        applier.resyncDrifted();
        clearInvocations(connector);

        assertThat(applier.resyncDrifted()).isZero();

        verify(connector, never())
                .bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(reload(demo).status()).isEqualTo(BindingStatus.PENDING);
        assertThat(notificationsOf(admin.id())).hasSize(1);
    }

    @Test
    @DisplayName("다시 보낸 설치가 바뀐 것이 없다고 답하고 probe 가 실패하면 PENDING 이고 알림은 반영 완료만 안내한다")
    void asksOnlyForConfirmWhenProbeFailsAfterReinstall() {
        when(connector.readConnector(agent.hermesProfile(), DEMO)).thenReturn(DRIFTED, INSTALLED);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(false, false));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));

        assertThat(applier.resyncDrifted()).isEqualTo(1);

        ConnectorBinding drifted = reload(demo);
        assertThat(drifted.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(drifted.restartRequired()).isFalse();
        List<Notification> sent = notificationsOf(admin.id());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body()).contains(CONFIRM_ONLY_BODY).doesNotContain(RESTART_BODY);
    }

    @Test
    @DisplayName("다시 보낸 설치가 reload_pending 이면 반영 예정 확인이 맡으므로 알림을 만들지 않는다")
    void doesNotNotifyWhenReinstallIsPendingReload() {
        when(connector.readConnector(agent.hermesProfile(), DEMO)).thenReturn(DRIFTED);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(false, false, true));

        assertThat(applier.resyncDrifted()).isEqualTo(1);

        ConnectorBinding drifted = reload(demo);
        assertThat(drifted.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(drifted.applyDueAt()).as("반영 예정 시각").isNotNull();
        assertThat(notificationsOf(admin.id())).isEmpty();
    }

    @Test
    @DisplayName("설치 상태를 읽지 못하면 그 바인딩만 건너뛰어 READY 로 남고 설치를 보내지 않는다")
    void skipsBindingWhenInstallStateReadFails(CapturedOutput output) {
        when(connector.readConnector(agent.hermesProfile(), DEMO)).thenThrow(new IllegalStateException("down"));

        assertThat(applier.resyncDrifted()).isZero();

        verify(connector, never())
                .bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        assertThat(reload(demo).status()).isEqualTo(BindingStatus.READY);
        assertThat(reload(plain).status()).isEqualTo(BindingStatus.READY);
        assertThat(notificationsOf(admin.id())).isEmpty();
        assertThat(output.getAll()).contains("connector " + DEMO + " failed at drift-check: IllegalStateException");
    }

    @Test
    @DisplayName("다시 맞춰 READY 가 된 뒤 또 어긋나기를 세 번 되풀이하면 세 번째는 설치를 보내지 않고 PENDING 으로 두고 알린다")
    void stopsReinstallingOnThirdConsecutiveDrift() {
        // 주기마다 점검의 읽기는 어긋나고, 다시 보낸 뒤의 읽기는 맞는다. 세 번째 주기는 점검의 읽기만 한다.
        when(connector.readConnector(agent.hermesProfile(), DEMO))
                .thenReturn(DRIFTED, INSTALLED, DRIFTED, INSTALLED, DRIFTED);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(false, false));

        assertThat(applier.resyncDrifted()).as("첫 번째").isEqualTo(1);
        assertThat(reload(demo).status()).isEqualTo(BindingStatus.READY);
        assertThat(applier.resyncDrifted()).as("두 번째").isEqualTo(1);
        assertThat(reload(demo).status()).isEqualTo(BindingStatus.READY);
        assertThat(notificationsOf(admin.id())).as("READY 로 다시 맞춘 주기").isEmpty();

        assertThat(applier.resyncDrifted()).as("세 번째").isZero();

        verify(connector, times(2))
                .bindConnector(eq(agent.hermesProfile()), eq(DEMO), anyString(), anyString(), nullable(String.class));
        assertThat(reload(demo).status()).isEqualTo(BindingStatus.PENDING);
        List<Notification> sent = notificationsOf(admin.id());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body()).contains(CONFIRM_ONLY_BODY);
    }

    @Test
    @DisplayName("한 주기에 읽는 수가 1 이면 READY 바인딩을 번호 순으로 하나씩 보고 끝까지 읽은 뒤 처음으로 돌아간다")
    void readsOneBindingPerRunWhenBatchIsOne() {
        ConnectorBindingApplier single = new ConnectorBindingApplier(
                service,
                bindings,
                agents,
                users,
                connector,
                transactions,
                clock,
                notifications,
                access,
                new ConnectorBindingProperties(Duration.ofSeconds(150), 1));
        // 다른 검사가 남긴 READY 바인딩이 있어도 번호 순서는 같다.
        List<ReadyBinding> ready = bindings.findReadyAfter(BindingStatus.READY, 0L, PageRequest.of(0, 1_000));
        assertThat(ready)
                .extracting(ReadyBinding::bindingId)
                .as("이 검사의 바인딩")
                .containsSubsequence(demo.id(), plain.id());

        for (ReadyBinding expected : ready) {
            clearInvocations(connector);
            single.resyncDrifted();
            verify(connector, times(1)).readConnector(anyString(), anyString());
            verify(connector).readConnector(expected.profile(), expected.connectorId());
        }
        clearInvocations(connector);
        single.resyncDrifted();
        verify(connector, never()).readConnector(anyString(), anyString());
        single.resyncDrifted();
        verify(connector, times(1)).readConnector(anyString(), anyString());
        verify(connector).readConnector(ready.get(0).profile(), ready.get(0).connectorId());
    }

    @Test
    @DisplayName("drift-batch 가 1 미만이면 설정을 만들지 못해 기동이 멈춘다")
    void rejectsDriftBatchBelowOne() {
        assertThatThrownBy(() -> new ConnectorBindingProperties(Duration.ofSeconds(150), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drift-batch");
    }

    private ConnectorBinding binding(String connectorId) {
        Long connectionId = connections
                .findByUserIdAndConnectorId(owner.id(), connectorId)
                .orElseThrow()
                .id();
        return bindings.findByAgentIdAndConnectionId(agent.id(), connectionId).orElseThrow();
    }

    private ConnectorBinding reload(ConnectorBinding binding) {
        return bindings.findById(binding.id()).orElseThrow();
    }

    private List<Notification> notificationsOf(Long userId) {
        return notificationRows.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10));
    }

    private Agent agent(CurrentUser user) {
        String code = "drift-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
    }

    private CurrentUser member() {
        AppUser saved = user(UserRole.MEMBER);
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    private AppUser user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        return users.save(AppUser.of("drift-" + suffix + "@example.com", suffix, GROUP, role, Instant.now()));
    }
}
