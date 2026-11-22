package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesGatewayClient;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.application.TurnSlot;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 정기 작업이 부르는 재시작 조회를 실제 HTTP 와 보안 필터로 본다(ADR-20261120 / connector-auto-apply, 스펙 REQ5).
 *
 * <p>토큰은 {@code application-test.yml} 의 검사 전용 값이다. 재시작 대기는 {@code plugin_updated} 답으로 만든다.
 */
@BackendIntegrationTest
class ConnectorRestartOpsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/ops/connector-restart";
    private static final String TOKEN = "test-ops-token";
    private static final String DEMO = "demo-notes";
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

    @LocalServerPort
    int port;

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
    HermesConnectorClient connector;

    @Autowired
    HermesGatewayClient gateway;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    TestClock clock;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    TransactionTemplate transactions;

    private CurrentUser owner;


    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(gateway.startedAt()).thenReturn(Optional.empty());
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap(), nullable(String.class)))
            .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        doReturn(new InstallResult(false, true, false))
            .when(connector).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        owner = user();
        connectionService.register(owner, DEMO, Map.of("token", "demo_ok_0123456789"));
    }


    @AfterEach
    void tearDown() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        jdbc.update("DELETE FROM allowed_person WHERE email = ?", owner.email());
        users.deleteAll();
        clock.reset();
    }


    @Test
    @DisplayName("토큰이 없거나 틀리면 401 이다")
    void rejectsMissingOrWrongToken() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(401);
        assertThat(get("Bearer wrong-token").statusCode()).isEqualTo(401);
        assertThat(get(TOKEN).statusCode()).as("Bearer 접두가 없다").isEqualTo(401);
    }


    @Test
    @DisplayName("재시작 대기가 없으면 0 이고, 있으면 실패와 껐다 켜기 바인딩을 뺀 수와 가장 오래된 대기 시각을 답한다")
    void countsOnlyRestartWaitsGatewayRestartResolves() throws Exception {
        JsonNode empty = ok();
        assertThat(empty.get("restartWaiting").longValue()).isZero();
        assertThat(empty.get("oldestWaitingSince").isNull()).isTrue();

        bindWaiting();
        clock.advance(Duration.ofMinutes(5));
        bindWaiting();
        ConnectorBinding failed = bindWaiting();
        jdbc.update("UPDATE agent_connector_binding SET apply_failed_at = ? WHERE id = ?",
            Timestamp.from(T0), failed.id());
        ConnectorBinding cycling = bindWaiting();
        jdbc.update("UPDATE agent_connector_binding SET cycle_phase = 'OFF', cycle_at = ? WHERE id = ?",
            Timestamp.from(T0), cycling.id());

        JsonNode waiting = ok();
        assertThat(waiting.get("restartWaiting").longValue()).isEqualTo(2);
        assertThat(Instant.parse(waiting.get("oldestWaitingSince").stringValue())).isBetween(T0, T0.plusMillis(1));
    }


    @Test
    @DisplayName("접근이 철회된 사용자의 대기는 재시작으로 풀리지 않으므로 세지 않는다")
    void skipsWaitsOfRevokedOwners() throws Exception {
        bindWaiting();
        assertThat(ok().get("restartWaiting").longValue()).isEqualTo(1);
        transactions.executeWithoutResult(status -> {
            AllowedPerson person = people.save(AllowedPerson.of(owner.email(), "사용자", "revoked-" + owner.id()));
            person.disable();
            people.saveAndFlush(person);
        });

        JsonNode waiting = ok();
        assertThat(waiting.get("restartWaiting").longValue()).isZero();
        assertThat(waiting.get("oldestWaitingSince").isNull()).isTrue();
    }


    @Test
    @DisplayName("진행 중인 실행 수는 turn 자리가 잡히면 늘고 돌려주면 준다")
    void countsRunningExecutions() throws Exception {
        int idle = ok().get("runningExecutions").intValue();

        TurnSlot slot = limiter.acquireTurn(owner.id());
        try {
            assertThat(ok().get("runningExecutions").intValue()).isEqualTo(idle + 1);
        } finally {
            slot.release();
        }

        assertThat(ok().get("runningExecutions").intValue()).isEqualTo(idle);
    }


    private JsonNode ok() throws Exception {
        HttpResponse<String> response = get("Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }


    private HttpResponse<String> get(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }


    private ConnectorBinding bindWaiting() {
        String code = "ops-" + UUID.randomUUID().toString().substring(0, 13);
        Agent agent = agents.save(Agent.of(
            code,
            code,
            "profile-" + code,
            "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id()));
        service.bind(owner, agent.code(), DEMO);
        List<ConnectorBinding> found = bindings.findByAgentId(agent.id());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).restartRequired()).isTrue();
        return found.get(0);
    }


    private CurrentUser user() {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("ops-" + suffix + "@example.com", suffix, 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
