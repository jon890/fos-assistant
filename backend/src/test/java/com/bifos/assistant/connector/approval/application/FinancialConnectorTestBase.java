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
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 plugin 과 loopback 토스 대역, Spring claim HTTP 를 띄우는 금융 통합 검사의 공용 준비다.
 *
 * <p>plugin 프로세스는 클래스마다 한 번 띄우고 검사마다 {@code /fixture/configure} 로 되돌린다. 되돌리는 범위와 다시 띄우는 조건은
 * {@link SharedPlugin} 이 갖는다.
 *
 * <p>검사 클래스는 이것을 상속하고 자기 {@code @Test} 만 둔다. 다른 검사 클래스를 상속하면 부모의 검사가 한 번 더 돌아 plugin 을 띄우는
 * 묶음이 늘어난다.
 */
@BackendIntegrationTest
@FinancialExecutionOpen
abstract class FinancialConnectorTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** {@code /fixture/configure} 가 되돌리는 대역 카운터의 처음 값이다. {@code url} 은 프로세스마다 다르므로 비교할 때 채운다. */
    static final ObjectNode INITIAL_COUNTERS = JSON.createObjectNode().put("auth", 0).put("financial", 0).put("deletes", 0)
        .put("prepare", 0).put("execute", 0);
    /** 상태를 바꾸지 않거나 {@code /fixture/configure} 가 되돌리는 조작이다. 나머지는 프로세스를 다시 띄우게 한다. */
    static final Set<String> RESETTABLE = Set.of("configure", "counters");
    /** 이 클래스의 검사가 함께 쓰는 plugin 프로세스다. JUnit 이 클래스를 차례로 돌리므로 한 번에 하나만 살아 있다. */
    static SharedPlugin shared;
    /** 이 클래스에서 plugin 을 띄운 횟수다. 클래스가 끝날 때 되돌리지 못한 검사 수 더하기 1 을 넘지 않는지 본다. */
    static int starts;
    static int dirtyTests;
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
        if (!Files.isDirectory(Path.of("../hermes/connectors/tossinvest/node_modules"))) {
            throw new IllegalStateException("tossinvest 의존성이 없다. bun install --frozen-lockfile --cwd hermes/connectors/tossinvest 를 먼저 돌린다.");
        }
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(now);
        if (shared == null) {
            shared = SharedPlugin.start(springPort, properties.dashboardToken());
        }
        pluginUrl = shared.url();
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
        // 앞 검사가 같은 프로세스에 남긴 카운터가 이 검사의 판정에 섞이지 않았는지 매번 확인한다.
        var reset = counters();
        assertThat(reset).as("configure 뒤 대역 카운터").isEqualTo(INITIAL_COUNTERS.deepCopy().put("url", reset.path("url").stringValue()));
    }


    @AfterEach
    void releasePlugin() throws Exception {
        try {
            // 되돌리지 못하는 상태를 남긴 검사 뒤에는 다음 검사가 새 프로세스를 띄운다.
            // 검사 도중 끝난 프로세스도 여기서 종료 코드를 확인해 기동 실패와 비정상 종료를 그 검사의 실패로 남긴다.
            if (shared != null && (shared.dirty() || !shared.process().isAlive())) {
                stopShared();
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


    @AfterAll
    static void stopPluginAfterClass() throws Exception {
        try {
            if (shared != null) {
                stopShared();
            }
            LoggerFactory.getLogger(FinancialConnectorTestBase.class).info("금융 plugin 기동 {}회, 되돌리지 못한 검사 {}건", starts, dirtyTests);
            assertThat(starts).as("plugin 기동 횟수(되돌리지 못한 검사 %d건)", dirtyTests).isLessThanOrEqualTo(dirtyTests + 1);
        } finally {
            starts = 0;
            dirtyTests = 0;
        }
    }


    private static void stopShared() throws InterruptedException {
        var process = shared.process();
        shared = null;
        process.destroy();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }


    /**
     * 클래스가 함께 쓰는 {@code producer_plugin_http.py} 프로세스다.
     *
     * <p>기동할 때 bun 거래 대역과 SDK 원고 생성이 함께 돌아 한 번에 2초 가까이 걸린다. 검사마다 새 profile 이름을 쓰고
     * {@code /fixture/configure} 가 대역 카운터와 응답 mode 를 되돌리므로 검사 사이에 다시 띄우지 않는다. 설치 원본을 다시 쓰는
     * {@code /fixture/manifest-change} 처럼 되돌리지 못하는 조작을 부른 검사 뒤에만 새로 띄운다.
     */
    static final class SharedPlugin {
        private final Process process;
        private final String url;
        private boolean dirty;


        private SharedPlugin(Process process, String url) {
            this.process = process;
            this.url = url;
        }


        static SharedPlugin start(int springPort, String token) throws Exception {
            var process = new ProcessBuilder("python3", "../test/e2e/financial/producer_plugin_http.py",
                "http://127.0.0.1:" + springPort, token).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try (var startup = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    String url = JSON.readTree(startup.submit(reader::readLine).get(30, TimeUnit.SECONDS)).get("url").stringValue();
                    starts++;
                    return new SharedPlugin(process, url);
                } catch (Exception failure) {
                    // 첫 줄이 오지 않으면 프로세스를 끝내 읽기 스레드와 executor 정리도 멈추지 않게 한다.
                    process.destroyForcibly();
                    throw failure;
                }
            }
        }


        Process process() {
            return process;
        }


        String url() {
            return url;
        }


        boolean dirty() {
            return dirty;
        }


        void markDirty() {
            if (!dirty) {
                dirtyTests++;
            }
            dirty = true;
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
        if (!RESETTABLE.contains(operation)) {
            shared.markDirty();
        }
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
