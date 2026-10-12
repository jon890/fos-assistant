package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 공통 조건 원문을 실제 검증·사용자 암호화·DB 저장 후 읽기에 적용한다. */
@BackendIntegrationTest
@Transactional
class ConnectorConditionalSnapshotTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode VECTOR = vectors();
    private static final JsonNode BASE = VECTOR.get("cases").get(0);
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final String DECLARATION = "[{\"arg\":\"account_seq\",\"field\":\"account\"}]";
    @Autowired
    ConnectorActionExecutionRepository contents;
    @Autowired
    ConnectorActionRepository actions;
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
    DataKeyService cipher;
    @Autowired
    ConnectorActionBodies bodies;
    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;


    @AfterEach
    void clearCipherCache() {
        cipher.forgetCachedKeys();
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedCases")
    @DisplayName("공통 원문을 검증하고 정상 vector는 실제 암호화 DB에서 다시 읽는다")
    void consumesSharedRawVector(String name, JsonNode sample) {
        Fixture fixture = fixture();
        ConnectorAction action = action(fixture.binding(), value(sample, "modelArgsJson"));
        if (!sample.get("valid").booleanValue()) {
            assertThatThrownBy(() -> validate(sample)).as(name).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> capture(action, fixture.binding(), sample)).as(name).isInstanceOf(IllegalArgumentException.class);
            return;
        }
        ConnectorExecutionSnapshot snapshot = validate(sample);
        assertThat(snapshot.requestKey(1L, 2L, value(sample, "tool"))).isEqualTo(value(sample, "requestKey"));
        assertThat(snapshot.executionArgsSha256()).isEqualTo(value(sample, "executionArgsSha256"));
        assertThat(snapshot.scopeSha256()).isEqualTo(value(sample, "scopeSha256"));
        ConnectorActionExecution row = contents.saveAndFlush(capture(action, fixture.binding(), sample));
        assertThat(row.contentKeyId()).isNotNull();
        em.clear();
        ConnectorExecutionSnapshot opened = ConnectorExecutionSnapshot.open(action, contents.findById(row.actionId()).orElseThrow(), cipher);
        assertThat(opened.executionArgsJson()).isEqualTo(value(sample, "executionArgsJson"));
        assertThat(opened.summaryJson()).isEqualTo(value(sample, "summaryJson"));
        assertThat(opened.scopeJson()).isEqualTo(value(sample, "scopeJson"));
    }


    @Test
    @DisplayName("조건 의도 키는 잘못된 surrogate를 거절하고 한글과 정상 비 BMP 해시를 보존한다")
    void rejectsMalformedToolEncodingAndPreservesUtf8Hash() {
        ConnectorExecutionSnapshot snapshot = validate(BASE);
        // primitive 호출만 검사한다. 두 tool 모두 manifest의 ASCII 등록 경계에서는 허용되지 않는다.
        assertThat(snapshot.requestKey(1L, 2L, "tool?")).hasSize(64);
        for (String tool : new String[] {"tool\uD800", "tool\uDC00", "tool\uD800?"}) {
            assertThatThrownBy(() -> snapshot.requestKey(1L, 2L, tool)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(snapshot.requestKey(1L, 2L, "도구😀"))
            .isEqualTo("0b37ca3b111348cc0319f633d016e521d3ff675b75cef78e4cbc98a4eee04650");
        assertThat(snapshot.requestKey(1L, 2L, value(BASE, "tool"))).isEqualTo(value(BASE, "requestKey"));
    }


    @Test
    @DisplayName("Spring 원문 경계의 공통 기대는 수용 47개와 거절 132개다")
    void verifiesActualRawConsumerCounts() {
        int accepted = 0;
        int rejected = 0;
        for (JsonNode sample : VECTOR.get("cases")) {
            if (sample.get("valid").booleanValue()) {
                validate(sample);
                accepted++;
            } else {
                assertThatThrownBy(() -> validate(sample)).as(value(sample, "name")).isInstanceOf(IllegalArgumentException.class);
                rejected++;
            }
        }
        assertThat(accepted).isEqualTo(47);
        assertThat(rejected).isEqualTo(132);
    }


    @Test
    @DisplayName("AAD의 열·사용자·action과 key 식별자를 바꾸면 DB 읽기가 실패한다")
    void rejectsStoredCipherContextTampering() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = contents.saveAndFlush(capture(fixture.action(), fixture.binding(), BASE));
        String sealed = row.executionArgsJson();
        for (String aad : new String[] {
            "connector_action_execution:" + fixture.action().id() + ":column:summary_json:user:" + fixture.action().userId(),
            "connector_action_execution:" + UUID.randomUUID() + ":column:execution_args_json:user:" + fixture.action().userId(),
            "connector_action_execution:" + fixture.action().id() + ":column:execution_args_json:user:" + (fixture.action().userId() + 1)}) {
            assertThat(cipher.open(row.contentKeyId(), fixture.action().userId(), aad, sealed)).isEmpty();
        }
        assertThat(cipher.open(row.contentKeyId() + 99999, fixture.action().userId(), "wrong", sealed)).isEmpty();
        jdbc.update("UPDATE connector_action_execution SET summary_json = execution_args_json WHERE action_id = ?", row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
            .isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("정상 암호문이어도 표시 내용이 달라지면 저장 후 읽기에서 거절한다")
    void rejectsStoredSemanticTampering() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = contents.saveAndFlush(capture(fixture.action(), fixture.binding(), BASE));
        String changed = value(BASE, "summaryJson").replace("\"quantity\":\"1\"", "\"quantity\":\"200\"");
        String aad = "connector_action_execution:" + fixture.action().id() + ":column:summary_json:user:" + fixture.action().userId();
        String sealed = cipher.seal(fixture.action().userId(), aad, changed).orElseThrow().content();
        jdbc.update("UPDATE connector_action_execution SET summary_json = ? WHERE action_id = ?", sealed, row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    @DisplayName("일반 주문에서 허용하는 account scope를 조건 DB 읽기에서는 거절한다")
    void rejectsStoredConditionalAccountScope() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = contents.saveAndFlush(capture(fixture.action(), fixture.binding(), BASE));
        String args = value(BASE, "executionArgsJson").replace("account_seq", "account");
        String scope = value(BASE, "scopeJson").replace("account_seq", "account");
        String prefix = "connector_action_execution:" + fixture.action().id() + ":column:";
        String suffix = ":user:" + fixture.action().userId();
        String sealedArgs = cipher.seal(fixture.action().userId(), prefix + "execution_args_json" + suffix, args).orElseThrow().content();
        String sealedScope = cipher.seal(fixture.action().userId(), prefix + "scope_json" + suffix, scope).orElseThrow().content();
        jdbc.update("UPDATE connector_action_execution SET execution_args_json = ?, scope_json = ?, execution_args_sha256 = ?, scope_sha256 = ? WHERE action_id = ?",
            sealedArgs, sealedScope, Sha256.hex(args), Sha256.hex(scope), row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
            .isInstanceOf(IllegalArgumentException.class);
    }


    private ConnectorActionExecution capture(ConnectorAction action, ConnectorBinding binding, JsonNode sample) {
        return ConnectorExecutionSnapshot.capture(action, binding, value(sample, "operation"), DECLARATION,
            value(sample, "executionArgsJson"), value(sample, "summaryJson"), value(sample, "scopeJson"), cipher, NOW);
    }


    private Fixture fixture() {
        AppUser user = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.com", "주인", 1L, UserRole.MEMBER, NOW));
        Agent agent = agents.saveAndFlush(Agent.of(
            "execution-" + UUID.randomUUID(),
            "검사용 에이전트",
            "execution-test-" + UUID.randomUUID(),
            "http://localhost",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            user.id(),
            NOW));
        ConnectorConnection connection = ConnectorConnection.pending(user.id(), "demo-financial", NOW);
        connection.connected(new ConnectionFields(Map.of("account", "1234"), Map.of("secret", "fake")), NOW);
        connection.ready(NOW);
        connection = connections.saveAndFlush(connection);
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, "demo", NOW.plusSeconds(1));
        binding.ready(NOW.plusSeconds(1));
        binding = bindings.saveAndFlush(binding);
        return new Fixture(action(binding, value(BASE, "modelArgsJson")), binding);
    }


    private ConnectorAction action(ConnectorBinding binding, String raw) {
        AgentExecution run = runs.saveAndFlush(AgentExecution.builder()
            .userId(binding.connection().userId())
            .agentId(binding.agent().id())
            .profileName(binding.agent().hermesProfile())
            .hermesSessionId("fos-" + UUID.randomUUID())
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(NOW)
            .build());
        ConnectorAction action = ConnectorAction.decided(
            binding.connection(),
            binding.agent().id(),
            run,
            "mcp__demo__place_order",
            "place_order",
            new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS),
            false,
            Sha256.hex(UUID.randomUUID().toString()),
            Sha256.hex(raw),
            NOW);
        action.awaitApproval(NOW.plusSeconds(300));
        actions.saveAndFlush(action);
        bodies.sealArgs(action, raw);
        return actions.saveAndFlush(action);
    }


    private static ConnectorExecutionSnapshot validate(JsonNode sample) {
        return ConnectorExecutionSnapshot.validate(
            value(sample, "modelArgsJson"),
            value(sample, "executionArgsJson"),
            value(sample, "summaryJson"),
            value(sample, "scopeJson"),
            value(sample, "operation"));
    }


    private static Stream<Arguments> sharedCases() {
        return VECTOR.get("cases").valueStream().map(sample -> Arguments.of(value(sample, "name"), sample));
    }


    private static JsonNode vectors() {
        try {
            return JSON.readTree(Files.readString(Path.of("../test/resources/conditional-snapshot-v1.json")));
        } catch (Exception e) {
            throw new IllegalStateException("공통 조건 vector를 읽지 못했다", e);
        }
    }


    private static String value(JsonNode node, String name) {
        return node.get(name).stringValue();
    }


    private record Fixture(ConnectorAction action, ConnectorBinding binding) {
    }
}

/** 같은 원문·암호화 DB 소비를 MySQL 스키마에서도 확인한다. */
@Tag("mysql")
class ConnectorConditionalSnapshotMysqlTest extends ConnectorConditionalSnapshotTest {
    @DynamicPropertySource
    static void selectTestDatabase(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
