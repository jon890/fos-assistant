package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.financial.application.FinancialDigest;
import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.infra.SandboxAttachmentDirectory;
import com.bifos.assistant.hermes.application.dto.ConnectorApprovedExecution;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FinancialExecutionOpen;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 plugin 과 loopback 토스 대역, Spring claim HTTP 를 띄우는 금융 통합 검사의 공용 준비다.
 *
 * <p>검사 클래스는 이것을 상속하고 자기 {@code @Test} 만 둔다. 다른 검사 클래스를 상속하면 부모의 검사가 한 번 더 돌아 plugin 을 띄우는
 * 묶음이 늘어난다.
 */
@BackendIntegrationTest
@FinancialExecutionOpen
abstract class FinancialConnectorTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TEST_DIGEST_KEY = "src/test/resources/financial/test-digest.key";
    static final String ARGS = "{\"symbol\":\"abc\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"quantity\":\"002\",\"price\":\"0012.3400\"}";
    Instant now;
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
            var owner = users.saveAndFlush(AppUser.of(id + "@example.test", "검사 주인", 1L, UserRole.MEMBER));
            profile = "producer-" + id;
            session = "fos-" + id;
            user = new CurrentUser(owner.id(), owner.email(), "검사 주인", 1L, UserRole.MEMBER);
            people.saveAndFlush(AllowedPerson.of(owner.email(), "검사 주인", profile));
            var agent = agents.saveAndFlush(Agent.of("producer-" + id, "검사", profile, "http://localhost",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id()));
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
        // 허용 목록 항목은 계좌와 그 주인 메일이다. 이 fixture 의 대역 계좌를 이 사용자에게 연다.
        OverridableLiveProperties.during(gate, financial(true, List.of("7:" + user.email()), gate.current()));
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
                jdbc.update("update app_user set limit_order_amount_krw = null, limit_order_amount_usd = null, limit_order_quantity = null, "
                    + "limit_daily_amount_krw = null, limit_daily_amount_usd = null, limit_updated_at = null where id = ?", user.id());
            }
            if (bindingId != null) {
                bindings.deleteById(bindingId);
            }
            if (connectionId != null) {
                connections.deleteById(connectionId);
            }
        }
    }


    void dropCheck(String table, String constraint) {
        String product = jdbc.execute((ConnectionCallback<String>)connection -> connection.getMetaData().getDatabaseProductName());
        jdbc.execute("alter table " + table + ("MySQL".equals(product) ? " drop check " : " drop constraint ") + constraint);
    }


    /** 상한은 {@code base} 그대로 두고 허용 목록과 key 파일만 바꾼다. */
    static ConnectorFinancialProperties financial(boolean keyed, List<String> accounts, ConnectorFinancialProperties base) {
        return new ConnectorFinancialProperties(accounts, base.maxOrderAmountKrw(), base.maxOrderAmountUsd(),
            base.maxOrderQuantity(), base.maxDailyAmountKrw(), base.maxDailyAmountUsd(), keyed ? TEST_DIGEST_KEY : "");
    }


    static ConnectorFinancialProperties financial(boolean keyed, List<String> accounts, String maxOrder) {
        BigDecimal order = new BigDecimal(maxOrder);
        return new ConnectorFinancialProperties(accounts, order, order, new BigDecimal("1000"),
            new BigDecimal("10000000"), new BigDecimal("100000"), keyed ? TEST_DIGEST_KEY : "");
    }


    static String agentSql() {
        return "(select agent_id from agent_connector_binding where id = ?)";
    }


    Long otherUser() {
        return tx().execute(status -> users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "다른 사람", 1L,
            UserRole.MEMBER))).id();
    }


    /** 이 fixture 의 사용자에게 연 허용 목록 항목이다. */
    List<String> owned(String account) {
        return List.of(account + ":" + user.email());
    }


    /** 이 fixture 의 루트 실행을 살펴보기 트리로 만든다. 다른 검사가 RUNNING 살펴보기 줄을 보지 않도록 호출자가 지운다. */
    Long checkTree(boolean writesAllowed) {
        return tx().execute(status -> {
            var run = runs.findAll().stream().filter(row -> session.equals(row.hermesSessionId())).findFirst().orElseThrow();
            var check = ProactiveCheck.started(user.id(), run.agentId(), run.conversationId(), CheckTrigger.MANUAL, writesAllowed, now);
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
