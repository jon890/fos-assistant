package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.SandboxAttachmentDirectory;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.FinancialExecutionOpen;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 테스트의 호출부만 실제 내부 업무 규칙에 연결한다. 공개 금융 승인을 열지 않는다. */
@BackendIntegrationTest
@FinancialExecutionOpen
class FinancialConnectorProducerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TEST_DIGEST_KEY = "src/test/resources/financial/test-digest.key";
    Instant now;
    static final String ARGS = "{\"symbol\":\"abc\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"quantity\":\"002\",\"price\":\"0012.3400\"}";
    @Autowired
    ConnectorFinancialProducer producer;
    @Autowired
    ProactiveCheckRepository checks;
    @Autowired
    ConnectorFinancialApproval approval;
    @Autowired
    LiveProperties<ConnectorFinancialProperties> gate;
    @Autowired
    FinancialDigest digest;
    @Autowired
    ConnectorActionRepository actions;
    @Autowired
    ConnectorActionExecutionRepository contents;
    @Autowired
    ConnectorConnectionRepository connections;
    @Autowired
    ConnectorBindingRepository bindings;
    @Autowired
    AgentRepository agents;
    @Autowired
    AgentExecutionRepository runs;
    @Autowired
    AppUserRepository users;
    @Autowired
    AllowedPersonRepository people;
    @Autowired
    HermesConnectorClient hermes;
    @Autowired
    HermesProperties properties;
    @Autowired
    SandboxAttachmentDirectory attachmentDirectory;
    @Autowired
    PlatformTransactionManager manager;
    @Autowired
    TestClock clock;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int springPort;
    Process plugin;
    String pluginUrl;
    HttpHermesConnectorClient client;
    CurrentUser user;
    String profile;
    String session;
    Long bindingId;
    Long connectionId;
    Instant connectionRevision;
    ConnectorApprovedExecution sent;


    @BeforeEach
    void setupProducer() throws Exception {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(now);
        plugin = new ProcessBuilder("python3", "../test/e2e/financial/producer_plugin_http.py",
            "http://127.0.0.1:" + springPort, properties.dashboardToken()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var reader = new BufferedReader(new InputStreamReader(plugin.getInputStream()));
        try (var startup = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                pluginUrl = JSON.readTree(startup.submit(reader::readLine).get(30, TimeUnit.SECONDS)).get("url").stringValue();
            } catch (Exception failure) {
                // 첫 줄이 오지 않으면 프로세스를 끝내 읽기 스레드와 executor 정리도 멈추지 않게 한다.
                plugin.destroyForcibly();
                throw failure;
            }
        }
        client = new HttpHermesConnectorClient(new HermesProperties(properties.profileKeyDir(), pluginUrl,
            properties.dashboardToken(), properties.sharedListenerBaseUrl(), null, null, Duration.ofSeconds(2), Duration.ofSeconds(5)), attachmentDirectory);
        doAnswer(invocation -> client.readCatalog()).when(hermes).readCatalog();
        doAnswer(invocation -> client.readConnector(invocation.getArgument(0), invocation.getArgument(1)))
            .when(hermes).readConnector(anyString(), anyString());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return client.prepare(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
        }).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent = invocation.getArgument(3);
            return client.executeApproved(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        tx().executeWithoutResult(status -> {
            String id = UUID.randomUUID().toString();
            var owner = users.saveAndFlush(AppUser.of(id + "@example.test", "검사 주인", 1L, UserRole.MEMBER, now));
            profile = "producer-" + id;
            session = "fos-" + id;
            user = new CurrentUser(owner.id(), owner.email(), "검사 주인", 1L, UserRole.MEMBER);
            people.saveAndFlush(AllowedPerson.of(owner.email(), "검사 주인", profile, now));
            var agent = agents.saveAndFlush(Agent.of("producer-" + id, "검사", profile, "http://localhost",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id(), now));
            var connection = ConnectorConnection.pending(owner.id(), "tossinvest", now);
            connection.connected(new ConnectionFields(Map.of("account", "7"), Map.of()), now);
            connection.ready(now);
            connections.saveAndFlush(connection);
            var binding = ConnectorBinding.pending(agent, connection, "tossinvest", now);
            binding.installed(false, now);
            binding.ready(now);
            bindings.saveAndFlush(binding);
            connectionId = connection.id();
            connectionRevision = connection.updatedAt();
            bindingId = binding.id();
            var conversation = conversations.saveAndFlush(Conversation.startedBy(owner.id(), "금융 시험", agent.id(), now));
            runs.saveAndFlush(AgentExecution.builder().userId(owner.id()).agentId(agent.id()).profileName(profile)
                .conversationId(conversation.id())
                .hermesSessionId(session).costMode(CostMode.SUBSCRIPTION).status(ExecutionStatus.RUNNING).startedAt(now).build());
        });
        control("configure", Map.of("profile", profile, "guard", Map.of("v", 1, "protocol", "approval-claim-v2",
            "bindingId", bindingId.toString(), "connectionId", connectionId.toString(), "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }


    @AfterEach
    void stopPlugin() throws Exception {
        try {
            if (plugin != null) {
                plugin.destroy();
                assertThat(plugin.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(plugin.exitValue()).isZero();
            }
        } finally {
            // 다음 대화 테스트가 사용자를 지울 수 있도록 이 fixture의 연결과 FK를 먼저 정리한다.
            if (user != null) {
                jdbc.update("delete from connector_action where user_id = ?", user.id());
            }
            if (bindingId != null) {
                bindings.deleteById(bindingId);
            }
            if (connectionId != null) {
                connections.deleteById(connectionId);
            }
        }
    }


    @Test
    @DisplayName("PENDING 승인과 암호화 snapshot을 같은 트랜잭션으로 저장한다")
    void storesPendingAndEncryptedSnapshotAtomically() {
        var answer = request();
        var action = action(answer);
        var row = contents.findById(action.id()).orElseThrow();
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(row.contentKeyId()).isNotNull();
        assertThat(row.executionArgsJson()).doesNotContain("clientOrderId", "ABC");
        assertThat(row.supersedesUnknownActionId()).isNull();
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isEqualTo(1);
    }


    @Test
    @DisplayName("snapshot 저장 실패는 승인과 알림도 롤백한다")
    void snapshotSaveFailureRollsBackPendingAndNotification() {
        long before = actions.count();
        jdbc.execute("alter table connector_action_execution add constraint producer_snapshot_failure check (binding_id <> " + bindingId + ")");
        try {
            assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
            assertThat(actions.count()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isZero();
            assertThat(counters().path("financial").intValue()).isZero();
        } finally {
            dropCheck("connector_action_execution", "producer_snapshot_failure");
        }
    }


    @Test
    @DisplayName("알림 저장 실패는 snapshot과 승인을 함께 롤백한다")
    void notificationSaveFailureRollsBackSnapshotAndPending() {
        long before = actions.count();
        long snapshots = contents.count();
        jdbc.execute("alter table notification add constraint producer_notification_failure check (user_id <> " + user.id() + ")");
        try {
            assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
            assertThat(actions.count()).isEqualTo(before);
            assertThat(contents.count()).isEqualTo(snapshots);
            assertThat(counters().path("financial").intValue()).isZero();
        } finally {
            dropCheck("notification", "producer_notification_failure");
        }
    }


    void dropCheck(String table, String constraint) {
        String product = jdbc.execute((ConnectionCallback<String>)connection -> connection.getMetaData().getDatabaseProductName());
        jdbc.execute("alter table " + table + ("MySQL".equals(product) ? " drop check " : " drop constraint ") + constraint);
    }


    @Test
    @DisplayName("prepare 실패는 승인과 snapshot을 남기지 않는다")
    void preparationFailureLeavesNoActionOrSnapshot() {
        long count = actions.count();
        long snapshots = contents.count();
        doReturn(CallResult.failure(ConnectorCallError.UNAVAILABLE)).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("새 clientOrderId로도 같은 금융 의도를 중복 생성하지 못한다")
    void rejectsDuplicateIntentWithAnotherGeneratedClientOrderId() {
        var first = request();
        assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
        assertThat(action(first).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("prepare").intValue()).isEqualTo(2);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("kill switch 가 꺼져 있으면 prepare 전에 거절하고 아무것도 저장하지 않는다")
    void closedSwitchRejectsBeforePrepare() {
        long count = actions.count();
        OverridableLiveProperties.during(gate, financial(false, List.of("7"), "1000000"));
        assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("prepare").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("허용 목록 밖의 계좌는 prepare 전에 거절한다")
    void accountOutsideAllowListRejectsBeforePrepare() {
        long count = actions.count();
        OverridableLiveProperties.during(gate, financial(true, List.of("9"), "1000000"));
        assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @Test
    @DisplayName("한 건 금액 상한을 넘는 주문은 승인 줄과 snapshot 을 남기지 않는다")
    void orderOverLimitStoresNothing() {
        long count = actions.count();
        long snapshots = contents.count();
        OverridableLiveProperties.during(gate, financial(true, List.of("7"), "1"));
        assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("승인 전에 kill switch 를 끄면 승인을 거절하고 PENDING 으로 남긴다")
    void approvalAfterSwitchClosedKeepsPending() {
        var requested = request();
        OverridableLiveProperties.during(gate, financial(false, List.of("7"), "1000000"));
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    static ConnectorFinancialProperties financial(boolean enabled, List<String> accounts, String maxOrder) {
        BigDecimal order = new BigDecimal(maxOrder);
        return new ConnectorFinancialProperties(enabled, accounts, order, order, new BigDecimal("1000"),
            new BigDecimal("10000000"), new BigDecimal("100000"), TEST_DIGEST_KEY);
    }


    @Test
    @DisplayName("prepare 뒤 암호화가 꺼지면 평문 snapshot과 승인과 알림을 저장하지 않는다")
    void rejectsDisabledEncryptionAfterPrepareWithoutSaving() {
        Object original = ReflectionTestUtils.getField(producer, "cipher");
        var changing = mock(TextCipher.class);
        when(changing.enabled()).thenReturn(true, false);
        long count = actions.count();
        long snapshots = contents.count();
        ReflectionTestUtils.setField(producer, "cipher", changing);
        try {
            assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        } finally {
            ReflectionTestUtils.setField(producer, "cipher", original);
        }
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id())).isZero();
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("prepare 뒤 바뀐 revision은 저장 전에 거부된다")
    void rejectsChangedRevisionAfterPrepareWithoutSaving() {
        doAnswer(invocation -> {
            var prepared = client.prepare(profile, "tossinvest", "mcp__tossinvest__create_order", ARGS);
            tx().executeWithoutResult(status -> bindings.findById(bindingId).orElseThrow().pending(now.plusSeconds(1)));
            return prepared;
        }).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        long count = actions.count();
        assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
        assertThat(actions.count()).isEqualTo(count);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest
    @DisplayName("권한과 선언이 맞지 않으면 prepare 전에 거부하고 아무것도 저장하지 않는다")
    @ValueSource(strings = {"cipher-disabled", "user-not-allowed", "read-only-check", "agent-disabled", "agent-owner",
        "connection-owner", "undeclared-tool"})
    void rejectsBeforePreparationWhenAuthorityOrDeclarationDiffers(String change) {
        long count = actions.count();
        long snapshots = contents.count();
        String toolName = "create_order";
        Object original = ReflectionTestUtils.getField(producer, "cipher");
        Long check = null;
        boolean disallowed = false;
        try {
            switch (change) {
                case "cipher-disabled" -> ReflectionTestUtils.setField(producer, "cipher", mock(TextCipher.class));
                case "user-not-allowed" -> disallowed = jdbc.update("delete from allowed_person where email = ?", user.email()) == 1;
                case "read-only-check" -> check = readOnlyCheck();
                case "agent-disabled" -> jdbc.update("update agent set enabled = false where id = " + agentSql(), bindingId);
                case "agent-owner" -> jdbc.update("update agent set owner_user_id = ? where id = " + agentSql(), otherUser(), bindingId);
                case "connection-owner" -> jdbc.update("update connector_connection set user_id = ? where id = ?", otherUser(), connectionId);
                default -> toolName = "get_orders";
            }
            String name = toolName;
            assertThatThrownBy(() -> producer.request(profile, session, session, UUID.randomUUID().toString(),
                "mcp__tossinvest__" + name, name, ARGS)).isInstanceOf(ApiException.class);
        } finally {
            ReflectionTestUtils.setField(producer, "cipher", original);
            // 다른 테스트가 RUNNING 살펴보기 줄을 보지 않도록 지운다.
            if (check != null) {
                checks.deleteById(check);
            }
            // 허용 목록 행을 지운 채 두면 같은 context를 쓰는 살펴보기 테스트가 전체 실행에서 실패한다.
            if (disallowed) {
                people.saveAndFlush(AllowedPerson.of(user.email(), "검사 주인", profile, now));
            }
        }
        assertThat(actions.count()).isEqualTo(count);
        assertThat(contents.count()).isEqualTo(snapshots);
        assertThat(counters().path("prepare").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    private static String agentSql() {
        return "(select agent_id from agent_connector_binding where id = ?)";
    }


    private Long otherUser() {
        return tx().execute(status -> users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "다른 사람", 1L,
            UserRole.MEMBER, now))).id();
    }


    private Long readOnlyCheck() {
        return tx().execute(status -> {
            var run = runs.findAll().stream().filter(row -> session.equals(row.hermesSessionId())).findFirst().orElseThrow();
            var check = ProactiveCheck.started(user.id(), run.agentId(), run.conversationId(), CheckTrigger.MANUAL, false, now);
            check.attachRoot(run.id(), session);
            return checks.saveAndFlush(check).id();
        });
    }


    ConnectorPolicyAnswer request() {
        return producer.request(profile, session, session, UUID.randomUUID().toString(), "mcp__tossinvest__create_order", "create_order", ARGS);
    }


    ConnectorAction action(ConnectorPolicyAnswer answer) {
        return actions.findByPublicId(answer.actionId()).orElseThrow();
    }


    TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }


    JsonNode counters() {
        return control("counters", Map.of());
    }


    JsonNode control(String operation, Object body) {
        try {
            return post(pluginUrl + "/fixture/" + operation, body, null);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    JsonNode post(String url, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }
}
