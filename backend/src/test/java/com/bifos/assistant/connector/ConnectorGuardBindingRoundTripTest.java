package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorBindingInstalls;
import com.bifos.assistant.connector.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorInstallAttempts;
import com.bifos.assistant.connector.application.ConnectorProperties;
import com.bifos.assistant.connector.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionGuard;
import com.bifos.assistant.connector.application.model.ConnectorBindingAttempt;
import com.bifos.assistant.connector.application.model.ConnectorInstallResult;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.application.SignInRevocation;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 실제 Spring MVC·DB와 Python 설치·상태·support 소비자의 순서와 금융 차단을 함께 검증한다. */
@BackendIntegrationTest
class ConnectorGuardBindingRoundTripTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00.123456Z");
    private static final String CONNECTOR = "demo-notes";
    @LocalServerPort
    int port;
    @Autowired
    HermesConnectorClient hermes;
    @Autowired
    HermesProperties properties;
    @Autowired
    ConnectorInstallAttempts attempts;
    @Autowired
    ConnectorBindingInstalls installs;
    @Autowired
    ConnectorBindingService bindingService;
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
    PlatformTransactionManager manager;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ConnectorActionService approvals;
    @Autowired
    BrowserGatewayTokens tokens;
    @Autowired
    TestClock clock;
    @Autowired
    SignInRevocation access;
    private ConnectorBindingHttpFixture remote;
    private ConnectorConnectionService concurrent;
    private Target target;


    @BeforeEach
    void setUp() throws Exception {
        target = null;
        clock.set(NOW);
        remote = new ConnectorBindingHttpFixture(port, properties.dashboardToken());
        remote.delegate(hermes);
        concurrent = new ConnectorConnectionService(connections, bindings, users, bindingService, hermes, manager,
            new ConnectorCallLimiter(new ConnectorProperties(2, 1000), clock), approvals, tokens, clock);
        target = new TransactionTemplate(manager).execute(status -> {
            String email = UUID.randomUUID() + "@example.test";
            var user = users.saveAndFlush(AppUser.of(email, "검사 주인", 1L, UserRole.MEMBER, NOW));
            people.saveAndFlush(AllowedPerson.of(email, "검사 주인", "alice", NOW));
            var agent = agents.saveAndFlush(Agent.of("guard-" + UUID.randomUUID(), "검사", "alice", "http://localhost",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id(), NOW));
            var connection = ConnectorConnection.pending(user.id(), CONNECTOR, NOW);
            connection.connected(new ConnectionFields(Map.of("scope", "000007"), Map.of()), NOW);
            connection.ready(NOW);
            connections.saveAndFlush(connection);
            var binding = bindings.saveAndFlush(ConnectorBinding.pending(agent, connection, "demo", NOW));
            return new Target(binding.id(), connection.id(), agent.id(), new CurrentUser(user.id(), email, "검사 주인", 1L, UserRole.MEMBER));
        });
        remote.actual.putVault(vault(), CONNECTOR, Map.of("token", "demo_ok_0123456789", "scope", "000007"));
    }


    @AfterEach
    void tearDown() throws Exception {
        if (remote != null) {
            remote.close();
        }
        if (target != null) {
            bindings.deleteById(target.bindingId());
            connections.deleteById(target.connectionId());
            // 에이전트 생성 사건이 만든 기본 Memory 허락도 이 시험의 행만 지운다.
            jdbc.update("delete from agent_memory_collection where agent_id = ?", target.agentId());
            agents.deleteById(target.agentId());
            jdbc.update("delete from allowed_person where email = ?", target.user().email());
            users.deleteById(target.user().id());
        }
    }


    @Test
    @DisplayName("커밋된 PENDING의 callback은 409이고 현재 결과 CAS READY 뒤 새 nonce만 verified가 된다")
    void verifiesOnlyAfterReadyCommitWithFreshNonce() throws Exception {
        var attempt = capture();
        remote.observeCommit.set(() -> observePending(attempt));
        assertThat(attempts.execute(attempt)).contains(ResyncOutcome.APPLY_SCHEDULED);
        assertThat(remote.callbackFailure.get()).as("별도 DB 연결의 시작 커밋 관측").isNull();
        assertThat(remote.callbackCodes).containsOnly(409);
        remote.observeCommit.set(null);
        clock.set(NOW.plusSeconds(300));
        var current = capture();
        assertThat(attempts.execute(current)).contains(ResyncOutcome.READY);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
        assertThat(connections.findById(target.connectionId()).orElseThrow().updatedAt()).isEqualTo(current.connectionRevision());
        assertThat(guardState()).isEqualTo("verified");
        assertThat(remote.callbackCodes.getLast()).isEqualTo(200);
        assertThat(remote.callbacks.stream().map(node -> node.get("nonce").stringValue()).distinct().count()).isEqualTo(remote.callbacks.size());
        JsonNode guard = remote.wires.getLast().get("bind").get("guard");
        assertThat(guard.propertyNames()).isEqualTo(Set.of("v", "protocol", "bindingId", "connectionId", "connectionUpdatedAt", "manifestSha256"));
        assertThat(guard.get("bindingId").stringValue()).isEqualTo(current.bindingId().toString());
        assertThat(guard.get("connectionId").stringValue()).isEqualTo(current.connectionId().toString());
        assertThat(guard.get("connectionUpdatedAt").stringValue()).isEqualTo(current.connectionRevision().toString());
        assertThat(guard.get("manifestSha256").stringValue()).isEqualTo(current.guardManifestSha256());
        var manifest = manifest();
        assertThat(ConnectorToolPolicies.find(manifest, "place_order").orElseThrow().grantable()).isFalse();
        for (ToolRisk risk : List.of(ToolRisk.FINANCIAL, ToolRisk.DESTRUCTIVE)) {
            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2,
                Optional.of(new ToolPolicy(risk, ToolApproval.ALWAYS, null, false)), true, 0,
                ToolPolicyDecision.CheckBoundary.NOT_CHECK).denyReason()).isEqualTo(ActionDenyReason.RISK_NOT_OPEN);
        }
        assertFinancialClosed();
    }


    @ParameterizedTest
    @ValueSource(strings = {"register", "check", "disconnect", "revoked", "owner", "profile", "revision"})
    @DisplayName("실제 설치 HTTP 응답보다 다음 등록·확인·해제·접근 및 맥락 변경이 먼저 커밋되면 옛 결과를 버린다")
    void discardsLateHttpAfterCommittedCompetition(String change) throws Exception {
        settle();
        var old = capture();
        var entered = new CountDownLatch(1);
        remote.entered.set(entered);
        remote.release.set(new CountDownLatch(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempts.execute(old));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // 별도 작업이 끝났다는 결과를 받은 뒤 첫 HTTP 응답을 풀어 잠금 유지 오류도 잡는다.
            pool.submit(() -> mutate(change)).get(5, TimeUnit.SECONDS);
            remote.release.get().countDown();
            if (change.equals("revoked")) {
                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            } else {
                assertThat(first.get(5, TimeUnit.SECONDS)).isEmpty();
            }
        } finally {
            remote.release.get().countDown();
        }
        if (!change.equals("register") && !change.equals("check")) {
            assertThat(guardState()).isNotEqualTo("verified");
        }
        assertFinancialClosed();
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 revision의 새 시도와 재생성한 결과 처리기는 늦은 성공·실패와 중복 결과를 한 번만 대조한다")
    void rejectsPreviousAndDuplicateAttemptResults(boolean failure) throws Exception {
        settle();
        var previous = capture();
        var entered = new CountDownLatch(1);
        remote.entered.set(entered);
        remote.release.set(new CountDownLatch(1));
        var result = new ConnectorInstallResult(null, false, failure ? ResyncOutcome.CALL_FAILED : ResyncOutcome.READY, null, false);
        var recreated = new ConnectorInstallAttempts(bindings, agents, users, installs, access,
            new TransactionTemplate(manager), new ConnectorExecutionGuard());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> attempts.execute(previous));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var current = capture();
            assertThat(recreated.execute(current)).contains(ResyncOutcome.READY);
            remote.heldFailure.set(failure ? 500 : 0);
            remote.release.get().countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(recreated.complete(current, result)).isEmpty();
        } finally {
            remote.release.get().countDown();
        }
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
        assertThat(guardState()).isEqualTo("verified");
        assertFinancialClosed();
    }


    @ParameterizedTest
    @ValueSource(strings = {"401", "403", "404", "nonce", "manifestSha256", "missingGuard", "changedSha", "restart"})
    @DisplayName("옛 backend와 plugin 및 nonce·SHA·guard·재시작 경계는 이전 verified를 재사용하지 않는다")
    void rechecksSupportAgainstRealConsumerBoundaries(String defect) throws Exception {
        settle();
        assertThat(guardState()).isEqualTo("verified");
        if (defect.matches("[0-9]+")) {
            remote.supportFailure.set(Integer.parseInt(defect));
        } else if (defect.equals("nonce") || defect.equals("manifestSha256")) {
            remote.supportDefect.set(defect);
        } else {
            remote.change(defect);
            if (defect.equals("restart")) {
                remote.supportFailure.set(404);
            }
        }
        assertThat(guardState()).isNotEqualTo("verified");
        if (defect.equals("changedSha")) {
            String previous = remote.wires.getLast().path("bind").path("guard").path("manifestSha256").stringValue();
            var next = capture();
            assertThat(next.guardManifestSha256()).isNotEqualTo(previous);
            attempts.execute(next);
            assertThat(remote.wires.getLast().path("bind").path("guard").path("manifestSha256").stringValue()).isEqualTo(next.guardManifestSha256());
        }
        assertFinancialClosed();
    }


    @Test
    @DisplayName("guard 없는 READ·WRITE는 기존 5인자로 설치되고 READY 판정을 보존한다")
    void keepsFiveArgumentInstallationWithoutGuard() throws Exception {
        remote.change("noGuard");
        settle();
        assertThat(remote.wires).allSatisfy(wire -> assertThat(wire.path("bind").has("guard")).isFalse());
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
        assertThat(ConnectorToolPolicies.find(manifest(), "write_note").orElseThrow().risk()).isEqualTo(ToolRisk.WRITE);
        assertThat(remote.callbacks).isEmpty();
    }


    @ParameterizedTest
    @ValueSource(strings = {"protocol", "missingSha", "badSha", "extra", "type"})
    @DisplayName("선언이 있는데 잘못됐으면 5인자 설치로 내려가지 않고 시작 트랜잭션을 거절한다")
    void rejectsInvalidDeclarationsBeforeHttp(String defect) {
        // 정상 선언과 SHA는 실제 Python loader가 만들었다. 시작 TX의 거절 경계만 변형한다.
        var declared = manifest();
        ObjectNode guard = (ObjectNode)declared.executionGuard().deepCopy();
        String sha = declared.executionGuardManifestSha256();
        if (defect.equals("protocol")) {
            guard.put("protocol", "unsupported");
        } else if (defect.equals("extra")) {
            guard.put("extra", true);
        }
        JsonNode node = defect.equals("type") ? ConnectorBindingHttpFixture.JSON.valueToTree(true) : guard;
        var invalid = new ConnectorManifest(declared.id(), declared.title(), declared.description(), declared.fields(),
            declared.verifyTool(), declared.mcpServer(), declared.toolsets(), declared.attachments(), declared.schema(), declared.tools(),
            declared.skills(), declared.appearance(), declared.singleBinding(), declared.ownerBrowser(), declared.ownerBrowserLoginUrl(),
            node, defect.equals("missingSha") ? null : defect.equals("badSha") ? "bad" : sha);
        assertThatThrownBy(() -> capture(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(remote.wires).isEmpty();
        assertThat(bindings.findById(target.bindingId()).orElseThrow().installAttemptId()).isNull();
    }


    @Test
    @DisplayName("옛 plugin의 설치 404는 READY로 승격하지 않고 현재 시도의 실패를 저장한다")
    void keepsOldPluginFailurePending() {
        remote.pluginFailure.set(404);
        assertThat(attempts.execute(capture())).contains(ResyncOutcome.CALL_FAILED);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.PENDING);
    }


    private void settle() {
        assertThat(attempts.execute(capture())).contains(ResyncOutcome.APPLY_SCHEDULED);
        clock.set(NOW.plusSeconds(300));
        assertThat(attempts.execute(capture())).contains(ResyncOutcome.READY);
    }


    private ConnectorManifest manifest() {
        return hermes.readCatalog().stream().filter(item -> item.id().equals(CONNECTOR)).findFirst().orElseThrow();
    }


    private ConnectorBindingAttempt capture() {
        return capture(manifest());
    }


    private ConnectorBindingAttempt capture(ConnectorManifest manifest) {
        return attempts.start(() -> {
            attempts.lockOwner(target.user().id());
            return attempts.begin(bindings.findById(target.bindingId()).orElseThrow(), Optional.of(manifest), Map.of(),
                true, true, false, clock.instant());
        });
    }


    private String guardState() {
        JsonNode guard = hermes.readConnector("alice", CONNECTOR).executionGuard();
        return guard == null ? "unsupported" : guard.path("state").stringValue();
    }


    private String vault() {
        return connections.findById(target.connectionId()).orElseThrow().vault();
    }


    private void observePending(ConnectorBindingAttempt attempt) {
        try (var connection = dataSource.getConnection();
            var query = connection.prepareStatement(
                "select b.status, b.install_attempt_id, c.updated_at from agent_connector_binding b join connector_connection c on c.id = b.connection_id where b.id = ?")) {
            query.setLong(1, attempt.bindingId());
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("PENDING");
                UUID id = attempt.attemptId();
                assertThat(rows.getBytes(2)).isEqualTo(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits()).array());
                assertThat(rows.getTimestamp(3, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant())
                    .isEqualTo(attempt.connectionRevision());
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }


    private void mutate(String change) {
        switch (change) {
            case "register" -> concurrent.register(target.user(), CONNECTOR, Map.of("token", "demo_ok_0123456789", "scope", "000007"));
            case "check" -> concurrent.check(target.user(), CONNECTOR);
            case "disconnect" -> connectionService.disconnect(target.user(), CONNECTOR);
            case "revoked" -> jdbc.update("update allowed_person set enabled = false where email = ?", target.user().email());
            case "owner" -> jdbc.update("update agent set owner_user_id = null where id = ?", target.agentId());
            case "profile" -> jdbc.update("update agent set hermes_profile = 'changed-profile' where id = ?", target.agentId());
            case "revision" -> jdbc.update("update connector_connection set updated_at = ? where id = ?", Timestamp.from(NOW.plusSeconds(600)), target.connectionId());
            default -> throw new AssertionError(change);
        }
    }


    private void assertFinancialClosed() throws Exception {
        JsonNode observed = remote.inspect();
        if (observed.get("entry").has("server")) {
            assertThat(observed.path("entry").path("server").path("tools").path("exclude").toString()).contains("place_order");
        }
        assertThat(observed.path("executionEnv").booleanValue()).isFalse();
        assertThat(observed.path("entry").toString()).doesNotContain("nonce", "ticket", "FOS_APPROVAL_");
        assertThat(observed.path("financialPosts").intValue()).isZero();
        assertThat(observed.path("financialDeletes").intValue()).isZero();
        assertThat(observed.path("authPosts").intValue()).isZero();
    }


    private record Target(Long bindingId, Long connectionId, Long agentId, CurrentUser user) {
    }
}
