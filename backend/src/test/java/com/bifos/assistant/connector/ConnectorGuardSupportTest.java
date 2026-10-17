package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.ToolPolicy;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.infra.SandboxAttachmentDirectory;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.AuthProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 공용 Spring 컨텍스트와 DB, 실제 필터 및 카탈로그 HTTP decoder를 함께 검사한다. */
@BackendIntegrationTest
class ConnectorGuardSupportTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUPPORT = "/internal/connector-executions/support";
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:02.123456Z");
    private static final String SHA = "a".repeat(64);
    private static final String CATALOG = """
            [{"id":"demo-financial","title":"검사용 금융","schema":2,
              "fields":[{"key":"account","env":"DEMO_ACCOUNT","secret":false}],
              "verify":{"tool":"prepare"},"mcp_server":"demo",
              "tools":{"prepare":{"risk":"READ","approval":"none"},
                "place_order":{"risk":"FINANCIAL","approval":"always"}},
              "execution_guard":{"protocol":"approval-claim-v2","prepare_tool":"prepare",
                "scope_fields":[{"arg":"account_seq","field":"account"}],
                "operations":{"place_order":"CREATE"}},
              "execution_guard_manifest_sha256":"%s"}]
            """.formatted(SHA);
    private final AtomicReference<String> catalog = new AtomicReference<>(CATALOG);
    private final AtomicInteger catalogReads = new AtomicInteger();
    private final AtomicReference<Runnable> beforeReply = new AtomicReference<>();
    private HttpServer remote;

    @LocalServerPort
    int port;
    @Autowired
    HermesConnectorClient hermes;
    @Autowired
    HermesProperties properties;
    @Autowired
    AuthProperties auth;
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
    JdbcTemplate jdbc;
    @Autowired
    AgentTokenRepository profileTokens;


    @BeforeEach
    void setUp() throws Exception {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/api/connectors/catalog", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer catalog-test-secret");
            catalogReads.incrementAndGet();
            Runnable mutation = beforeReply.getAndSet(null);
            if (mutation != null) {
                mutation.run();
            }
            byte[] answer = catalog.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(answer);
            }
        });
        remote.start();
        String base = "http://127.0.0.1:" + remote.getAddress().getPort();
        var actual = new HttpHermesConnectorClient(new HermesProperties("unused", base, "catalog-test-secret", base,
            null, null, Duration.ofSeconds(1), Duration.ofSeconds(1)),
            new SandboxAttachmentDirectory(System.getProperty("java.io.tmpdir")));
        // 공용 mock은 테스트 안에서 직접 만든 실제 HTTP 클라이언트에 위임한다.
        when(hermes.readCatalog()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return actual.readCatalog();
        });
    }


    @AfterEach
    void tearDown() {
        remote.stop(0);
        profileTokens.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        people.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("서비스 Bearer로 현재 바인딩을 확인하고 요청마다 nonce와 guard 타입을 그대로 응답한다")
    void echoesOnlyCurrentNonceAndGuardFields() throws Exception {
        Long executionsBefore = jdbc.queryForObject("select count(*) from connector_action_execution", Long.class);
        var request = request(fixture());
        for (int i = 0; i < 2; i++) {
            request.put("nonce", UUID.randomUUID().toString());
            var response = post(SUPPORT, request.toString(), properties.dashboardToken(), false);
            assertThat(response.statusCode()).isEqualTo(200);
            var expected = request.deepCopy();
            expected.remove(List.of("profile", "connectorId"));
            assertThat(JSON.readTree(response.body())).isEqualTo(expected);
        }
        assertThat(catalogReads.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from connector_action_execution", Long.class)).isEqualTo(executionsBefore);
        for (ToolRisk risk : new ToolRisk[] {ToolRisk.FINANCIAL, ToolRisk.DESTRUCTIVE}) {
            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2,
                Optional.of(new ToolPolicy(risk, ToolApproval.ALWAYS, null, false)), true, 0,
                ToolPolicyDecision.CheckBoundary.NOT_CHECK).denyReason()).isEqualTo(ActionDenyReason.RISK_NOT_OPEN);
        }
    }


    @Test
    @DisplayName("JWT와 profile 토큰과 서명된 ticket은 서비스 인증을 대신하지 못한다")
    void rejectsEveryOtherAuthenticationSource() throws Exception {
        Fixture f = fixture();
        Long usersBefore = jdbc.queryForObject("select count(*) from app_user", Long.class);
        var profileToken = profileTokens.saveAndFlush(AgentToken.issueFor(
            f.profile(), Sha256.hex("test-profile-bearer"), "support 인증 검사"));
        String jwt = Jwts.builder().subject(f.email())
            .signWith(Keys.hmacShaKeyFor(auth.jwtSecret().getBytes(StandardCharsets.UTF_8))).compact();
        var vector = JSON.readTree(Files.readString(Path.of("../test/resources/financial-ticket-v1.json")));
        String ticket = vector.get("payloadBase64Url").stringValue() + "." + vector.get("signatureBase64Url").stringValue();
        for (String token : new String[] {null, jwt, "test-profile-bearer", ticket, properties.dashboardToken() + "x"}) {
            assertThat(post(SUPPORT, request(f).toString(), token, false).statusCode()).isEqualTo(401);
        }
        assertThat(catalogReads.get()).isZero();
        assertThat(profileTokens.findById(profileToken.id()).orElseThrow().lastUsedAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(usersBefore);
    }


    @Test
    @DisplayName("다른 사용자의 현재 바인딩도 요청 profile이 다르면 확인할 수 없다")
    void rejectsOtherUsersBindingFromRequestProfile() throws Exception {
        var own = fixture();
        var other = fixture();
        var request = request(other).put("profile", own.profile());
        assertThat(post(SUPPORT, request.toString(), properties.dashboardToken(), false).statusCode()).isEqualTo(409);
        assertThat(catalogReads.get()).isZero();
    }


    @ParameterizedTest
    @ValueSource(strings = {"revoked", "pending", "revision", "deleted", "bindingRevision", "restart", "owner", "profile"})
    @DisplayName("카탈로그 HTTP가 진행되는 동안 바뀐 DB 상태는 성공 응답으로 되살리지 않는다")
    void rejectsChangesDuringCatalogHttp(String defect) throws Exception {
        var f = fixture();
        beforeReply.set(() -> {
            switch (defect) {
                case "revoked" -> jdbc.update("update allowed_person set enabled = false where email = ?", f.email());
                case "pending" -> jdbc.update("update agent_connector_binding set status = 'PENDING' where id = ?", f.bindingId());
                case "revision" -> jdbc.update("update connector_connection set updated_at = ? where id = ?",
                    Timestamp.from(NOW.plusSeconds(1)), f.connectionId());
                case "deleted" -> bindings.deleteById(f.bindingId());
                // 실제 DDL의 DATETIME(6)에 맞춰 UTC 마이크로초 값으로 바인딩 revision만 바꾼다.
                // HTTP 전후 조회는 각각 새 EntityManager를 쓰므로 JDBC 변경을 다시 읽어야 한다.
                case "bindingRevision" -> {
                    Instant changed = NOW.plusNanos(1000);
                    assertThat(jdbc.update("update agent_connector_binding set updated_at = ? where id = ?",
                        OffsetDateTime.ofInstant(changed, ZoneOffset.UTC), f.bindingId())).isEqualTo(1);
                    Instant reread = new TransactionTemplate(manager).execute(status -> bindings.findById(f.bindingId()).orElseThrow().updatedAt());
                    assertThat(reread).isEqualTo(changed);
                }
                case "restart" -> assertThat(jdbc.update("update agent_connector_binding set restart_required = true where id = ?",
                    f.bindingId())).isEqualTo(1);
                case "owner" -> assertThat(jdbc.update("update agent set owner_user_id = null where id = ?", f.agentId())).isEqualTo(1);
                case "profile" -> assertThat(jdbc.update("update agent set hermes_profile = ? where id = ?",
                    "changed-" + f.profile(), f.agentId())).isEqualTo(1);
                default -> throw new AssertionError(defect);
            }
        });
        assertThat(post(SUPPORT, request(f).toString(), properties.dashboardToken(), false).statusCode()).isEqualTo(409);
        assertThat(catalogReads.get()).isEqualTo(1);
    }


    @ParameterizedTest
    @ValueSource(strings = {"vBool", "vDecimal", "vExponent", "vText", "idNumber", "idBool", "idDecimal",
        "idZero", "idOverflow", "revisionNano", "revisionOffset", "hashUpper", "hashBool", "nonceBool",
        "nonceInvalid", "profileLong", "connectorLong", "additional", "duplicate", "trailing", "nested", "oversized"})
    @DisplayName("추가 키와 중복 키, 틀린 타입과 길이와 비정규 nonce는 카탈로그를 읽기 전에 거절한다")
    void rejectsMalformedRequestsBeforeCatalog(String defect) throws Exception {
        ObjectNode request = request(fixture());
        String body = switch (defect) {
            case "vBool" -> request.put("v", true).toString();
            case "vDecimal" -> request.put("v", 1.0).toString();
            case "vExponent" -> request.toString().replace("\"v\":1", "\"v\":1e0");
            case "vText" -> request.put("v", "1").toString();
            case "idNumber" -> request.put("bindingId", 1).toString();
            case "idBool" -> request.put("bindingId", true).toString();
            case "idDecimal" -> request.put("bindingId", "1.0").toString();
            case "idZero" -> request.put("bindingId", "0").toString();
            case "idOverflow" -> request.put("bindingId", "9223372036854775808").toString();
            case "revisionNano" -> request.put("connectionUpdatedAt", "2026-10-10T00:00:02.123456789Z").toString();
            case "revisionOffset" -> request.put("connectionUpdatedAt", "2026-10-10T00:00:02.123456+00:00").toString();
            case "hashUpper" -> request.put("manifestSha256", "A".repeat(64)).toString();
            case "hashBool" -> request.put("manifestSha256", true).toString();
            case "nonceBool" -> request.put("nonce", true).toString();
            case "nonceInvalid" -> request.put("nonce", "nonce").toString();
            case "profileLong" -> request.put("profile", "p".repeat(65)).toString();
            case "connectorLong" -> request.put("connectorId", "c".repeat(65)).toString();
            case "additional" -> request.put("extra", 1).toString();
            case "duplicate" -> request.toString().replace("\"v\":1", "\"v\":1,\"v\":1");
            case "trailing" -> request + "{}";
            case "nested" -> request.set("profile", JSON.readTree("{\"x\":{\"y\":{}}}")).toString();
            case "oversized" -> " ".repeat(4096) + request;
            default -> throw new AssertionError(defect);
        };
        assertThat(post(SUPPORT, body, properties.dashboardToken(), true).statusCode()).isEqualTo(400);
        assertThat(catalogReads.get()).isZero();
    }


    @ParameterizedTest
    @ValueSource(strings = {"missing", "connectionId", "revision", "profile", "connectorId", "manifest", "owner",
        "revoked", "connectionPending", "bindingPending", "disabled", "agentDisabled", "server"})
    @DisplayName("현재 바인딩과 소유자, profile, revision, manifest가 다르면 지원 확인을 거절한다")
    void rejectsStaleOrUnauthorizedBinding(String defect) throws Exception {
        Fixture f = fixture();
        ObjectNode request = request(f);
        switch (defect) {
            case "missing" -> request.put("bindingId", Long.toString(Long.MAX_VALUE));
            case "connectionId" -> request.put("connectionId", Long.toString(Long.MAX_VALUE));
            case "revision" -> request.put("connectionUpdatedAt", NOW.minusSeconds(1).toString());
            case "profile" -> request.put("profile", "other-profile");
            case "connectorId" -> request.put("connectorId", "other-connector");
            case "manifest" -> request.put("manifestSha256", "b".repeat(64));
            case "owner" -> jdbc.update("update agent set owner_user_id = null where id = ?", f.agentId());
            case "revoked" -> jdbc.update("update allowed_person set enabled = false where email = ?", f.email());
            case "connectionPending" -> jdbc.update("update connector_connection set status = 'PENDING' where id = ?", f.connectionId());
            case "bindingPending" -> jdbc.update("update agent_connector_binding set status = 'PENDING' where id = ?", f.bindingId());
            case "disabled" -> jdbc.update("update agent_connector_binding set desired_enabled = false where id = ?", f.bindingId());
            case "agentDisabled" -> jdbc.update("update agent set enabled = false where id = ?", f.agentId());
            case "server" -> jdbc.update("update agent_connector_binding set mcp_server = 'other' where id = ?", f.bindingId());
            default -> throw new AssertionError(defect);
        }
        assertThat(post(SUPPORT, request.toString(), properties.dashboardToken(), false).statusCode()).isEqualTo(409);
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "metadata", "missingGuard", "guardBool", "guardExtra", "kindsEmpty", "kindsTool", "kindsValue",
        "hashBool", "hashMissing", "hashShort"})
    @DisplayName("캐시된 성공 대신 새 카탈로그의 전체 manifest 해시와 원형 보호 선언을 대조한다")
    void rechecksFreshCatalogAndStrictGuard(String defect) throws Exception {
        ObjectNode request = request(fixture());
        assertThat(post(SUPPORT, request.toString(), properties.dashboardToken(), false).statusCode()).isEqualTo(200);
        ObjectNode manifest = (ObjectNode)JSON.readTree(CATALOG).get(0);
        switch (defect) {
            case "metadata" -> {
                manifest.put("title", "바뀐 제목");
                manifest.put("execution_guard_manifest_sha256", "b".repeat(64));
            }
            case "missingGuard" -> manifest.remove("execution_guard");
            case "guardBool" -> manifest.put("execution_guard", true);
            case "guardExtra" -> ((ObjectNode)manifest.get("execution_guard")).put("extra", true);
            case "kindsEmpty" -> ((ObjectNode)manifest.get("execution_guard")).putObject("kinds");
            case "kindsTool" -> ((ObjectNode)manifest.get("execution_guard")).putObject("kinds").put("prepare", "ORDER");
            case "kindsValue" -> ((ObjectNode)manifest.get("execution_guard")).putObject("kinds").put("place_order", "TRADE");
            case "hashBool" -> manifest.put("execution_guard_manifest_sha256", true);
            case "hashMissing" -> manifest.remove("execution_guard_manifest_sha256");
            case "hashShort" -> manifest.put("execution_guard_manifest_sha256", "abc");
            default -> throw new AssertionError(defect);
        }
        catalog.set("[" + manifest + "]");
        assertThat(post(SUPPORT, request.toString(), properties.dashboardToken(), false).statusCode()).isEqualTo(409);
        assertThat(catalogReads.get()).isEqualTo(2);
    }


    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "extra", "suffix"})
    @DisplayName("정확한 POST support만 열고 인접 경로와 다른 메서드는 인증 경계를 유지한다")
    void keepsExactSupportBoundary(String method) throws Exception {
        String uri = SUPPORT + (method.equals("extra") ? "/extra" : method.equals("suffix") ? "-extra" : "");
        if (method.equals("extra") || method.equals("suffix")) {
            assertThat(post(uri, "{}", null, false).statusCode()).isIn(401, 403);
            return;
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
            .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
        }
    }


    private Fixture fixture() {
        return new TransactionTemplate(manager).execute(status -> {
            String id = UUID.randomUUID().toString();
            String profile = "support-" + id;
            var user = users.saveAndFlush(AppUser.of(id + "@example.test", "검사 주인", 1L, UserRole.MEMBER));
            people.saveAndFlush(AllowedPerson.of(user.email(), "검사 주인", profile));
            var agent = agents.saveAndFlush(Agent.of("support-" + id, "검사", profile, "http://localhost",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
            var connection = ConnectorConnection.pending(user.id(), "demo-financial", NOW);
            connection.connected(new ConnectionFields(Map.of("account", "000007"), Map.of()), NOW);
            connection.ready(NOW);
            connections.saveAndFlush(connection);
            var binding = ConnectorBinding.pending(agent, connection, "demo", NOW);
            binding.installed(false, NOW);
            binding.ready(NOW);
            bindings.saveAndFlush(binding);
            // 같은 시각의 상태 전이도 revision을 올리므로 저장한 연결의 현재 값을 요청에 쓴다.
            return new Fixture(binding.id(), connection.id(), connection.updatedAt(), agent.id(), profile, user.email());
        });
    }


    private ObjectNode request(Fixture f) {
        return JSON.createObjectNode().put("v", 1).put("protocol", "approval-claim-v2")
            .put("bindingId", f.bindingId().toString()).put("connectionId", f.connectionId().toString())
            .put("connectionUpdatedAt", f.connectionUpdatedAt().toString()).put("manifestSha256", SHA)
            .put("nonce", UUID.randomUUID().toString()).put("profile", f.profile()).put("connectorId", "demo-financial");
    }


    private HttpResponse<String> post(String uri, String body, String token, boolean chunked) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(
            () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) : HttpRequest.BodyPublishers.ofString(body);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(publisher);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }


    private record Fixture(Long bindingId, Long connectionId, Instant connectionUpdatedAt, Long agentId, String profile, String email) {
    }
}
