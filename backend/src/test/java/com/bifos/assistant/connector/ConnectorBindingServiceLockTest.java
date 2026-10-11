package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.AgentConnectionView;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 반영 완료가 주인의 사용자 행 잠금을 트랜잭션의 첫 문장으로 기다리는지 본다(ADR-083).
 *
 * <p>{@link ConnectorBindingServiceMysqlLockTest} 가 같은 검사를 MySQL 에서 돌린다. 테스트 클래스에 트랜잭션을 두지 않는다. 서비스의
 * 트랜잭션과 행 잠금이 실제로 돌아야 동시 요청의 차례를 볼 수 있다. 대시보드의 커넥터, 스킬, 도구 목록 경로만 대역이다.
 */
@BackendIntegrationTest
class ConnectorBindingServiceLockTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    protected static final String DEMO = "demo-notes";
    protected static final Map<String, String> VALUES = Map.of("token", "demo_ok_0123456789");
    protected static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
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
    /** 이 시각 뒤의 설치는 모두 관리자가 본 뒤의 설치다. */
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

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
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesSkillClient skills;

    @Autowired
    HermesToolsetClient toolsets;

    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new InstallResult(true, false));
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString()))
                .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(skills.list(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    /**
     * 다른 트랜잭션이 주인의 사용자 행을 잠근 동안 반영 완료는 기다리고, 커밋된 새 재시작 대기 시각을 보고 거절한다.
     *
     * <p>반영 완료의 트랜잭션이 잠금 없는 읽기로 시작하면 MySQL 의 REPEATABLE READ 에서는 그때 읽기 시점이 정해져, 잠금을
     * 기다린 뒤에도 바인딩의 옛 시각을 읽고 대기를 풀어 버린다. 앞선 트랜잭션은 새 재시작 시각을 적고 잠금을 쥔 자리에서 멈춘다.
     */
    @Test
    @DisplayName("사용자 행 잠금을 기다린 반영 완료는 커밋된 새 재시작 대기를 보고 CONNECTOR_RESTART_AGAIN 이다")
    void confirmAppliedWaitsForRegistrationAndSeesNewRestartWait() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        jdbc.update("UPDATE agent_connector_binding SET restart_required_since = ?", Timestamp.from(LONG_AGO));
        Instant shown = shownTo(admin);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> registering =
                    pool.submit(() -> transactions.executeWithoutResult(status -> {
                        users.findByIdForUpdate(owner.id()).orElseThrow();
                        jdbc.update("UPDATE agent_connector_binding SET restart_required_since = ?", Timestamp.from(Instant.now()));
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .as("다른 트랜잭션이 사용자 행 잠금을 쥐었다")
                    .isTrue();
            CountDownLatch waiting = new CountDownLatch(1);
            doAnswer(invocation -> {
                waiting.countDown();
                jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, owner.id());
                return users.findById(owner.id());
            }).when(users).findByIdForUpdate(owner.id());
            Future<AgentConnectionView> confirming =
                    pool.submit(() -> service.confirmApplied(admin, agent.code(), DEMO, shown));
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(confirming.isDone()).as("반영 완료가 사용자 행 잠금을 기다린다").isFalse();

            release.countDown();

            registering.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> confirming.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTOR_RESTART_AGAIN));
            ConnectorBinding stored = onlyBinding();
            assertThat(stored.status()).isEqualTo(BindingStatus.PENDING);
            assertThat(stored.restartRequired()).isTrue();
            assertThat(stored.restartRequiredSince()).isAfter(LONG_AGO);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 반영 완료가 트랜잭션 밖에서 주인을 읽은 뒤 주인이 바뀌면, 잠근 사용자 행이 지금 주인의 것이 아니라 대기를 풀지 않는다. */
    @Test
    @DisplayName("반영 완료가 주인을 읽은 뒤 잠금을 기다리는 사이 주인이 바뀌면 AGENT_BUSY 이고 대기를 풀지 않는다")
    void confirmAppliedRefusesWhenOwnerChangesWhileWaiting() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser next = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> registering =
                    pool.submit(() -> transactions.executeWithoutResult(status -> {
                        users.findByIdForUpdate(owner.id()).orElseThrow();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .as("다른 트랜잭션이 사용자 행 잠금을 쥐었다")
                    .isTrue();
            CountDownLatch waiting = new CountDownLatch(1);
            doAnswer(invocation -> {
                waiting.countDown();
                jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, owner.id());
                return users.findById(owner.id());
            }).when(users).findByIdForUpdate(owner.id());
            Future<AgentConnectionView> confirming =
                    pool.submit(() -> service.confirmApplied(admin, agent.code(), DEMO, null));
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(confirming.isDone()).as("반영 완료가 사용자 행 잠금을 기다린다").isFalse();
            jdbc.update("UPDATE agent SET owner_user_id = ? WHERE id = ?", next.id(), agent.id());

            release.countDown();

            registering.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> confirming.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_BUSY));
            assertThat(onlyBinding().restartRequired()).as("재시작 대기가 풀리지 않았다").isTrue();
            verify(connector, never()).probe(anyString(), anyString());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 관리자 목록이 보인 그 바인딩의 재시작 대기 시작 시각이다. */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private Instant shownTo(CurrentUser admin) {
        List<AdminConnectionSnapshot> listed = connectionService.listForAdmin(admin);
        assertThat(listed).hasSize(1);
        return listed.get(0).restartRequiredSince();
    }

    private void connect(CurrentUser owner, String connectorId, Map<String, String> values) {
        connectionService.register(owner, connectorId, values);
    }

    private ConnectorBinding onlyBinding() {
        List<ConnectorBinding> all = bindings.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    protected Agent agent(CurrentUser owner, AgentVisibility visibility) {
        String code = "lock-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                owner.id(),
                Instant.now()));
    }

    protected CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("lock-" + suffix + "@example.com", suffix, groupId, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
