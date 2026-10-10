package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.crypto.application.DataKeyService;
import com.bifos.assistant.crypto.application.DataKeyWriter;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.crypto.infra.DataEncryptionProperties;
import com.bifos.assistant.crypto.infra.FileKeyEncryptionKeys;
import com.bifos.assistant.crypto.infra.UserDataKeyRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 공유 vector와 실제 사용자 암호화·저장소로 저장 및 읽기의 실패 경계를 확인한다. 외부 요청은 보내지 않는다. */
@BackendIntegrationTest
@Transactional
class ConnectorActionExecutionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode VECTOR = vectors();
    private static final JsonNode BASE = VECTOR.get("cases").get(0);
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00.123456Z");
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
    DataKeyWriter writer;

    @Autowired
    UserDataKeyRepository keys;

    @Autowired
    DataEncryptionProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void clearCipherCache() {
        cipher.forgetCachedKeys();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedCases")
    @DisplayName("공통 정상·거절 vector를 실제 strict 소비 함수로 검증한다")
    void consumesSharedVector(String name, JsonNode sample) {
        if (sample.get("valid").booleanValue()) {
            ConnectorExecutionSnapshot snapshot = validate(sample);
            assertThat(snapshot.executionArgsJson()).as(name).isEqualTo(value(sample, "executionArgsJson"));
            assertThat(snapshot.executionArgsSha256()).isEqualTo(value(sample, "executionArgsSha256"));
            assertThat(snapshot.scopeSha256()).isEqualTo(value(sample, "scopeSha256"));
            assertThat(snapshot.requestKey(901L, 701L, value(sample, "tool"))).isEqualTo(value(sample, "requestKey"));
        } else {
            assertThatThrownBy(() -> validate(sample))
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid financial execution snapshot");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collisionCases")
    @DisplayName("side 변경 우회와 clientOrderId scope 충돌을 저장 전과 DB 읽기에서 거절한다")
    void rejectsSharedScopeCollisionsBeforeCaptureAndAfterReload(String name, JsonNode sample) {
        Fixture fixture = fixture();
        ConnectorBinding binding = fixture.binding();
        Map<String, String> publicFields = new HashMap<>();
        for (var entry : sample.get("publicFields").properties()) {
            publicFields.put(entry.getKey(), entry.getValue().stringValue());
        }
        binding.connection().connected(new ConnectionFields(publicFields, Map.of()), NOW);
        ConnectorAction action = action(binding, value(sample, "modelArgsJson"));
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.capture(
                        action,
                        binding,
                        value(sample, "operation"),
                        sample.get("scopeFields").toString(),
                        value(sample, "executionArgsJson"),
                        value(sample, "summaryJson"),
                        value(sample, "scopeJson"),
                        cipher,
                        NOW))
                .as(name)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid financial execution snapshot");
        assertThat(contents.existsById(action.id())).isFalse();

        // 과거 저장 내용을 재현해 원문 해시가 맞아도 strict 검증에서 거절하는지 확인한다.
        contents.saveAndFlush(ConnectorActionExecution.stored(
                action,
                binding.connection().id(),
                binding.id(),
                binding.connection().updatedAt(),
                binding.updatedAt(),
                value(sample, "executionArgsJson"),
                value(sample, "summaryJson"),
                value(sample, "scopeJson"),
                null,
                Sha256.hex(value(sample, "executionArgsJson")),
                Sha256.hex(value(sample, "scopeJson")),
                "0".repeat(64),
                ConnectorExecutionSnapshot.PROTOCOL,
                NOW));
        em.clear();
        ConnectorAction reloaded = actions.findById(action.id()).orElseThrow();
        ConnectorActionExecution row = contents.findById(action.id()).orElseThrow();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(reloaded, row, cipher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid financial execution snapshot")
                .satisfies(error -> assertThat(error.getStackTrace())
                        .extracting(StackTraceElement::getMethodName)
                        .contains("validate"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "orderId",
                "symbol",
                "market",
                "currency",
                "side",
                "quantity",
                "orderAmount",
                "price",
                "orderType",
                "timeInForce",
                "status",
                "filledQuantity",
                "execution",
                "clientOrderId",
                "expected_order",
                "confirmHighValueOrder",
                "userId",
                "connectionId",
                "tool"
            })
    @DisplayName("주문 필드 전체와 내부 메타데이터의 이름은 scope로 쓸 수 없다")
    void rejectsEveryReservedScopeName(String name) {
        ObjectNode args = (ObjectNode) JSON.readTree(value(BASE, "executionArgsJson"));
        JsonNode existing = args.get(name);
        String scopeValue = existing != null && existing.isString() ? existing.stringValue() : "reserved";
        args.put(name, scopeValue);
        ObjectNode scope = JSON.createObjectNode().put("account_seq", "000007").put(name, scopeValue);
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.validate(
                        value(BASE, "modelArgsJson"),
                        args.toString(),
                        value(BASE, "summaryJson"),
                        scope.toString(),
                        "CREATE"))
                .isInstanceOf(IllegalArgumentException.class);
        Fixture fixture = fixture();
        fixture.binding()
                .connection()
                .connected(new ConnectionFields(Map.of("account", "000007", "reserved", scopeValue), Map.of()), NOW);
        String declaration =
                "[{\"arg\":\"account_seq\",\"field\":\"account\"},{\"arg\":\"" + name + "\",\"field\":\"reserved\"}]";
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.capture(
                        fixture.action(),
                        fixture.binding(),
                        "CREATE",
                        declaration,
                        args.toString(),
                        value(BASE, "summaryJson"),
                        scope.toString(),
                        cipher,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(contents.existsById(fixture.action().id())).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {"account", "tenant", "user", "connection", "side_scope", "clientOrderId_scope", "tool_scope"})
    @DisplayName("예약 이름과 다른 정상 scope 및 주문 이름의 공개 연결 칸을 유지한다")
    void preservesNonReservedScopeNamesAndPublicFieldNames(String name) {
        Fixture fixture = fixture();
        fixture.binding().connection().connected(new ConnectionFields(Map.of("side", "000007"), Map.of()), NOW);
        String args = value(BASE, "executionArgsJson").replace("account_seq", name);
        String scope = value(BASE, "scopeJson").replace("account_seq", name);
        String declaration = "[{\"arg\":\"" + name + "\",\"field\":\"side\"}]";
        ConnectorActionExecution row = contents.saveAndFlush(ConnectorExecutionSnapshot.capture(
                fixture.action(),
                fixture.binding(),
                "CREATE",
                declaration,
                args,
                value(BASE, "summaryJson"),
                scope,
                cipher,
                NOW));
        em.clear();
        ConnectorExecutionSnapshot opened = ConnectorExecutionSnapshot.open(
                actions.findById(row.actionId()).orElseThrow(),
                contents.findById(row.actionId()).orElseThrow(),
                cipher);
        assertThat(opened.executionArgsJson()).isEqualTo(args);
        assertThat(opened.summaryJson()).isEqualTo(value(BASE, "summaryJson"));
        assertThat(opened.scopeJson()).isEqualTo(scope);
    }

    @Test
    @DisplayName("암호화한 세 본문을 같은 key로 저장하고 두 revision 및 원래 인자를 보존한다")
    void storesAndOpensActualCiphertext() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        assertThat(row.contentKeyId()).isPositive();
        assertThat(row.executionArgsJson()).startsWith("v1.").isNotEqualTo(value(BASE, "executionArgsJson"));
        assertThat(row.summaryJson()).startsWith("v1.");
        assertThat(row.scopeJson()).startsWith("v1.");
        assertThat(row.ticketId()).isNull();
        assertThat(row.consumedAt()).isNull();
        assertThat(row.supersedesUnknownActionId()).isNull();
        assertThat(row.connectionUpdatedAt())
                .isEqualTo(fixture.binding().connection().updatedAt());
        assertThat(row.bindingUpdatedAt())
                .isEqualTo(fixture.binding().updatedAt())
                .isNotEqualTo(row.connectionUpdatedAt());
        em.clear();
        ConnectorAction action = actions.findById(fixture.action().id()).orElseThrow();
        ConnectorActionExecution stored = contents.findById(action.id()).orElseThrow();
        ConnectorExecutionSnapshot opened = ConnectorExecutionSnapshot.open(action, stored, cipher);
        assertThat(opened.executionArgsJson()).isEqualTo(value(BASE, "executionArgsJson"));
        assertThat(opened.summaryJson()).isEqualTo(value(BASE, "summaryJson"));
        assertThat(opened.scopeJson()).isEqualTo(value(BASE, "scopeJson"));
        assertThat(action.argsJson()).isEqualTo(value(BASE, "modelArgsJson"));
        assertThat(action.argsSha256()).isEqualTo(Sha256.hex(value(BASE, "modelArgsJson")));
        assertThat(action.grantAllowed()).isFalse();
    }

    @Test
    @DisplayName("같은 의도는 여러 행에 저장할 수 있고 clientOrderId만 달라도 같은 키다")
    void intentIsNotUniqueAndClientIdDoesNotChangeIt() {
        Fixture fixture = fixture();
        ConnectorActionExecution first = save(fixture, cipher);
        ConnectorAction secondAction = action(fixture.binding(), value(BASE, "modelArgsJson"));
        JsonNode sample = VECTOR.get("cases").get(1);
        ConnectorActionExecution second = contents.saveAndFlush(ConnectorExecutionSnapshot.capture(
                secondAction,
                fixture.binding(),
                "CREATE",
                DECLARATION,
                value(sample, "executionArgsJson"),
                value(sample, "summaryJson"),
                value(sample, "scopeJson"),
                cipher,
                NOW));
        assertThat(second.requestKey()).isEqualTo(first.requestKey());
        assertThat(second.executionArgsSha256()).isNotEqualTo(first.executionArgsSha256());
        assertThat(contents.findByRequestKeyOrderByActionIdAsc(first.requestKey()))
                .hasSize(2);
        assertThat(validate(BASE).requestKey(902L, 701L, "place_order")).isNotEqualTo(value(BASE, "requestKey"));
        assertThat(validate(BASE).requestKey(901L, 702L, "place_order")).isNotEqualTo(value(BASE, "requestKey"));
        assertThat(validate(BASE).requestKey(901L, 701L, "other_tool")).isNotEqualTo(value(BASE, "requestKey"));
    }

    @Test
    @DisplayName("같은 승인 번호를 쓰는 두 새 객체의 저장은 기존 내용을 덮어쓰지 않고 거절한다")
    void refusesSecondNewEntityForSameAction() {
        Fixture fixture = fixture();
        ConnectorActionExecution first = save(fixture, cipher);
        assertThat(first.isNew()).isFalse();
        em.clear();
        ConnectorAction reloaded = actions.findById(first.actionId()).orElseThrow();
        ConnectorActionExecution second = ConnectorExecutionSnapshot.capture(
                reloaded,
                fixture.binding(),
                "CREATE",
                DECLARATION,
                value(BASE, "executionArgsJson"),
                value(BASE, "summaryJson"),
                value(BASE, "scopeJson"),
                cipher,
                NOW);
        assertThat(second.isNew()).isTrue();
        assertThatThrownBy(() -> contents.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("NULL keyId는 설정과 무관하게 평문을 읽으며 해시 변조를 거절한다")
    void plaintextUsesStoredKeyIdAndChecksHashes() {
        Fixture fixture = fixture();
        TextCipher disabled = new ForcedCipher(cipher, false);
        ConnectorActionExecution row = save(fixture, disabled);
        assertThat(row.contentKeyId()).isNull();
        assertThat(ConnectorExecutionSnapshot.open(fixture.action(), row, cipher)
                        .executionArgsJson())
                .isEqualTo(value(BASE, "executionArgsJson"));
        assertThat(ConnectorExecutionSnapshot.open(fixture.action(), row, disabled)
                        .executionArgsJson())
                .isEqualTo(value(BASE, "executionArgsJson"));
        jdbc.update(
                "UPDATE connector_action_execution SET execution_args_json = ? WHERE action_id = ?",
                value(BASE, "executionArgsJson") + " ",
                row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(
                        fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비NULL keyId는 현재 enabled가 거짓이어도 실제 복호화를 거친다")
    void encryptedReadDoesNotDependOnCurrentEnabledFlag() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        assertThat(ConnectorExecutionSnapshot.open(fixture.action(), row, new ForcedCipher(cipher, false))
                        .scopeJson())
                .isEqualTo(value(BASE, "scopeJson"));
        DataEncryptionProperties disabled = new DataEncryptionProperties("", "", false, Duration.ofMinutes(1), 20);
        DataKeyService withoutKek =
                new DataKeyService(new FileKeyEncryptionKeys(disabled), writer, keys, disabled, Clock.systemUTC());
        assertThat(withoutKek.enabled()).isFalse();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), row, withoutKek))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("financial snapshot decryption failed");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("실제 key 삭제 뒤 캐시를 비우면 암호문 읽기를 거절한다")
    void missingActualDataKeyCannotUseCachedSecret() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        withCommittedFixture(fixture -> {
            Long keyId = transactions.execute(status -> {
                ConnectorActionExecution row =
                        contents.findById(fixture.action().id()).orElseThrow();
                assertThat(keys.existsById(row.contentKeyId())).isTrue();
                cipher.forgetCachedKeys();
                assertThat(ConnectorExecutionSnapshot.open(fixture.action(), row, cipher)
                                .executionArgsJson())
                        .isEqualTo(value(BASE, "executionArgsJson"));
                return row.contentKeyId();
            });
            transactions.executeWithoutResult(status -> {
                keys.deleteById(keyId);
                keys.flush();
                assertThat(keys.existsById(keyId)).isFalse();
            });
            cipher.forgetCachedKeys();
            transactions.executeWithoutResult(status -> {
                assertThat(keys.existsById(keyId)).isFalse();
                ConnectorActionExecution row =
                        contents.findById(fixture.action().id()).orElseThrow();
                assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), row, cipher))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("financial snapshot decryption failed");
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("새 암호화 서비스 인스턴스도 같은 DB에서 세 원문을 정확히 다시 읽는다")
    void freshCipherReadsPersistedContentWithoutOldCaches() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        withCommittedFixture(fixture -> transactions.executeWithoutResult(status -> {
            ConnectorActionExecution row =
                    contents.findById(fixture.action().id()).orElseThrow();
            assertThat(row.isNew()).isFalse();
            assertThat(keys.existsById(row.contentKeyId())).isTrue();
            DataKeyService fresh = new DataKeyService(
                    new FileKeyEncryptionKeys(properties), writer, keys, properties, Clock.systemUTC());
            ConnectorExecutionSnapshot opened = ConnectorExecutionSnapshot.open(
                    actions.findById(row.actionId()).orElseThrow(), row, fresh);
            assertThat(opened.executionArgsJson()).isEqualTo(value(BASE, "executionArgsJson"));
            assertThat(opened.summaryJson()).isEqualTo(value(BASE, "summaryJson"));
            assertThat(opened.scopeJson()).isEqualTo(value(BASE, "scopeJson"));
        }));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("aadCases")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("커밋한 정상 본문을 다른 행·칸·소유자에 옮기면 새 DB 읽기에서 거절한다")
    void aadBindsActualCiphertextToOwnerRowAndColumn(String boundary, String column) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        withCommittedFixture(source -> {
            boolean anotherOwner = boundary.startsWith("owner");
            Fixture target = "column".equals(boundary)
                    ? source
                    : transactions.execute(status -> {
                        Fixture created = anotherOwner
                                ? fixture()
                                : new Fixture(action(source.binding(), value(BASE, "modelArgsJson")), source.binding());
                        save(created, cipher);
                        return created;
                    });
            try {
                // REQUIRES_NEW로 만든 key도 보이는 새 트랜잭션에서 정상 읽기를 먼저 확인한다.
                cipher.forgetCachedKeys();
                transactions.executeWithoutResult(status -> {
                    em.clear();
                    assertPersistedBodies(source);
                    if (target != source) {
                        assertPersistedBodies(target);
                    }
                    ConnectorActionExecution sourceRow =
                            contents.findById(source.action().id()).orElseThrow();
                    ConnectorActionExecution targetRow =
                            contents.findById(target.action().id()).orElseThrow();
                    if (anotherOwner) {
                        assertThat(source.action().userId())
                                .isNotEqualTo(target.action().userId());
                        assertThat(sourceRow.contentKeyId()).isNotEqualTo(targetRow.contentKeyId());
                    } else {
                        assertThat(sourceRow.contentKeyId()).isEqualTo(targetRow.contentKeyId());
                    }
                });
                transactions.executeWithoutResult(status -> {
                    em.clear();
                    ConnectorActionExecution sourceRow =
                            contents.findById(source.action().id()).orElseThrow();
                    ConnectorActionExecution targetRow =
                            contents.findById(target.action().id()).orElseThrow();
                    String fromColumn = "column".equals(boundary)
                            ? ("scope_json".equals(column) ? "summary_json" : "scope_json")
                            : column;
                    String sealed = jdbc.queryForObject(
                            "SELECT " + fromColumn + " FROM connector_action_execution WHERE action_id = ?",
                            String.class,
                            sourceRow.actionId());
                    Long movedKey =
                            "owner-source-key".equals(boundary) ? sourceRow.contentKeyId() : targetRow.contentKeyId();
                    assertThat(jdbc.update(
                                    "UPDATE connector_action_execution SET " + column
                                            + " = ?, content_key_id = ? WHERE action_id = ?",
                                    sealed,
                                    movedKey,
                                    targetRow.actionId()))
                            .isEqualTo(1);
                    em.clear();
                });
                cipher.forgetCachedKeys();
                transactions.executeWithoutResult(status -> {
                    em.clear();
                    ConnectorAction reloaded =
                            actions.findById(target.action().id()).orElseThrow();
                    ConnectorActionExecution row =
                            contents.findById(reloaded.id()).orElseThrow();
                    assertThat(keys.existsById(row.contentKeyId())).isTrue();
                    assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(reloaded, row, cipher))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("financial snapshot decryption failed");
                });
            } finally {
                if (target != source) {
                    transactions.executeWithoutResult(status -> {
                        if (anotherOwner) {
                            deleteFixture(target);
                        } else {
                            jdbc.update(
                                    "DELETE FROM connector_action WHERE id = ?",
                                    target.action().id());
                            jdbc.update(
                                    "DELETE FROM agent_execution WHERE id = ?",
                                    target.action().originExecutionId());
                        }
                    });
                }
            }
        });
    }

    private void assertPersistedBodies(Fixture fixture) {
        ConnectorAction action = actions.findById(fixture.action().id()).orElseThrow();
        ConnectorActionExecution row = contents.findById(action.id()).orElseThrow();
        assertThat(keys.existsById(row.contentKeyId())).isTrue();
        ConnectorExecutionSnapshot opened = ConnectorExecutionSnapshot.open(action, row, cipher);
        assertThat(opened.executionArgsJson()).isEqualTo(value(BASE, "executionArgsJson"));
        assertThat(opened.summaryJson()).isEqualTo(value(BASE, "summaryJson"));
        assertThat(opened.scopeJson()).isEqualTo(value(BASE, "scopeJson"));
    }

    private static Stream<Arguments> aadCases() {
        return Stream.of("row", "column", "owner-source-key", "owner-target-key")
                .flatMap(boundary -> Stream.of("execution_args_json", "summary_json", "scope_json")
                        .map(column -> Arguments.of(boundary, column)));
    }

    @Test
    @DisplayName("실제 암호문 변조와 저장한 scope 해시 변조를 거절한다")
    void rejectsCiphertextAndScopeHashTampering() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        String broken =
                row.executionArgsJson().substring(0, row.executionArgsJson().length() - 8) + "AAAAAAAA";
        jdbc.update(
                "UPDATE connector_action_execution SET execution_args_json = ? WHERE action_id = ?",
                broken,
                row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(
                        fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update(
                "UPDATE connector_action_execution SET execution_args_json = ?, scope_sha256 = ? WHERE action_id = ?",
                row.executionArgsJson(),
                "0".repeat(64),
                row.actionId());
        em.clear();
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(
                        fixture.action(), contents.findById(row.actionId()).orElseThrow(), cipher))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("세 seal의 keyId가 다르거나 enabled 상태에서 빈 seal이면 행을 저장하지 않는다")
    void refusesMismatchedKeysAndEmptySeal() {
        Fixture fixture = fixture();
        TextCipher mismatched = mock(TextCipher.class);
        when(mismatched.enabled()).thenReturn(true);
        when(mismatched.seal(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new SealedText("fake-a", 1L)))
                .thenReturn(Optional.of(new SealedText("fake-b", 2L)));
        assertThatThrownBy(() -> save(fixture, mismatched)).isInstanceOf(IllegalArgumentException.class);
        TextCipher empty = mock(TextCipher.class);
        when(empty.enabled()).thenReturn(true);
        when(empty.seal(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> save(fixture, empty)).isInstanceOf(IllegalStateException.class);
        assertThat(contents.existsById(fixture.action().id())).isFalse();
    }

    @Test
    @DisplayName("enabled 상태의 빈 open을 평문이나 빈 성공으로 바꾸지 않는다")
    void refusesEmptyOpen() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        TextCipher empty = mock(TextCipher.class);
        when(empty.enabled()).thenReturn(true);
        when(empty.open(anyLong(), anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ConnectorExecutionSnapshot.open(fixture.action(), row, empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("financial snapshot decryption failed");
    }

    @Test
    @DisplayName("scope 선언은 공개 연결 칸만 허용하고 누락·추가·중복·타입 오류를 거절한다")
    void declarationCannotReadSecretsOrChangePublicScope() {
        Fixture fixture = fixture();
        for (String bad : new String[] {
            "[{\"arg\":\"account_seq\",\"field\":\"secret\"}]",
            "[{\"arg\":\"account_seq\",\"field\":\"account\",\"extra\":1}]",
            "[{\"arg\":\"account_seq\",\"field\":\"account\"},{\"arg\":\"account_seq\",\"field\":\"other\"}]",
            "[{\"arg\":\"account_seq\",\"field\":\"account\"},{\"arg\":\"other\",\"field\":\"account\"}]",
            "[{\"arg\":1,\"field\":\"account\"}]",
            "[]",
            "[{\"arg\":\"account_seq\",\"arg\":\"other\",\"field\":\"account\"}]"
        }) {
            assertThatThrownBy(() -> ConnectorExecutionSnapshot.capture(
                            fixture.action(),
                            fixture.binding(),
                            "CREATE",
                            bad,
                            value(BASE, "executionArgsJson"),
                            value(BASE, "summaryJson"),
                            value(BASE, "scopeJson"),
                            cipher,
                            NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        fixture.binding().connection().connected(new ConnectionFields(Map.of("account", "8"), Map.of()), NOW);
        assertThatThrownBy(() -> save(fixture, cipher)).isInstanceOf(IllegalArgumentException.class);
        assertThat(contents.existsById(fixture.action().id())).isFalse();
    }

    @Test
    @DisplayName("저장 함수는 다른 소유자의 바인딩을 사용하지 않는다")
    void cannotUseAnotherOwnersBinding() {
        Fixture fixture = fixture();
        ReflectionTestUtils.setField(
                fixture.action(), "userId", fixture.action().userId() + 1000);
        assertThatThrownBy(() -> save(fixture, cipher)).isInstanceOf(IllegalArgumentException.class);
        assertThat(contents.existsById(fixture.action().id())).isFalse();
    }

    @Test
    @DisplayName("승인 줄을 지우면 실행 내용도 지우고 연결이나 바인딩 삭제는 이력을 남긴다")
    void actionOwnsLifetimeButConnectionAndBindingDoNot() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        jdbc.update(
                "DELETE FROM agent_connector_binding WHERE id = ?",
                fixture.binding().id());
        jdbc.update(
                "DELETE FROM connector_connection WHERE id = ?",
                fixture.binding().connection().id());
        assertThat(contents.existsById(row.actionId())).isTrue();
        jdbc.update("DELETE FROM connector_action WHERE id = ?", row.actionId());
        assertThat(contents.existsById(row.actionId())).isFalse();
    }

    @Test
    @DisplayName("불변 본문은 관리 엔티티를 바꿔도 다시 저장되지 않는다")
    void immutableContentIsNotOverwritten() {
        Fixture fixture = fixture();
        ConnectorActionExecution row = save(fixture, cipher);
        String sealed = row.executionArgsJson();
        ReflectionTestUtils.setField(row, "executionArgsJson", "changed");
        contents.flush();
        em.clear();
        assertThat(contents.findById(row.actionId()).orElseThrow().executionArgsJson())
                .isEqualTo(sealed);
    }

    private ConnectorActionExecution save(Fixture fixture, TextCipher selected) {
        return contents.saveAndFlush(ConnectorExecutionSnapshot.capture(
                fixture.action(),
                fixture.binding(),
                "CREATE",
                DECLARATION,
                value(BASE, "executionArgsJson"),
                value(BASE, "summaryJson"),
                value(BASE, "scopeJson"),
                selected,
                NOW));
    }

    /** 쓰기를 커밋한 뒤 별도 읽기 경계를 검사하고 이 fixture의 정확한 번호만 정리한다. */
    private void withCommittedFixture(Consumer<Fixture> assertion) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Fixture fixture = transactions.execute(status -> {
            Fixture created = fixture();
            save(created, cipher);
            return created;
        });
        try {
            assertion.accept(fixture);
        } finally {
            transactions.executeWithoutResult(status -> deleteFixture(fixture));
        }
    }

    private void deleteFixture(Fixture fixture) {
        jdbc.update(
                "DELETE FROM connector_action WHERE id = ?", fixture.action().id());
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", fixture.action().originExecutionId());
        jdbc.update(
                "DELETE FROM agent_connector_binding WHERE id = ?",
                fixture.binding().id());
        jdbc.update(
                "DELETE FROM connector_connection WHERE id = ?",
                fixture.binding().connection().id());
        jdbc.update(
                "DELETE FROM agent_memory_collection WHERE agent_id = ?",
                fixture.binding().agent().id());
        jdbc.update("DELETE FROM agent WHERE id = ?", fixture.binding().agent().id());
        jdbc.update(
                "DELETE FROM user_data_key WHERE user_id = ?", fixture.action().userId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", fixture.action().userId());
    }

    private Fixture fixture() {
        AppUser user =
                users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.com", "주인", 1L, UserRole.MEMBER, NOW));
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
        connection.connected(new ConnectionFields(Map.of("account", "000007"), Map.of("secret", "fake")), NOW);
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
        action.awaitApproval(raw, NOW.plusSeconds(300));
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

    private static Stream<Arguments> collisionCases() {
        return VECTOR.get("cases")
                .valueStream()
                .filter(sample -> value(sample, "name").startsWith("scope-collision-"))
                .map(sample -> Arguments.of(value(sample, "name"), sample));
    }

    private static Stream<Arguments> sharedCases() {
        return VECTOR.get("cases").valueStream().map(sample -> Arguments.of(value(sample, "name"), sample));
    }

    private static JsonNode vectors() {
        try {
            return JSON.readTree(Files.readString(Path.of("../test/fixtures/financial-approval-v1.json")));
        } catch (Exception e) {
            throw new IllegalStateException("공통 금융 vector를 읽지 못했다", e);
        }
    }

    private static String value(JsonNode node, String name) {
        return node.get(name).stringValue();
    }

    private record Fixture(ConnectorAction action, ConnectorBinding binding) {}

    private record ForcedCipher(TextCipher delegate, boolean enabled) implements TextCipher {
        @Override
        public Optional<SealedText> seal(Long ownerUserId, String aad, String plain) {
            return delegate.seal(ownerUserId, aad, plain);
        }

        @Override
        public Optional<String> open(Long keyId, Long ownerUserId, String aad, String sealed) {
            return delegate.open(keyId, ownerUserId, aad, sealed);
        }
    }
}

/** H2와 같은 저장 회귀를 실제 MySQL에 적용한다. mysqlMigrationTest가 이 클래스를 함께 돌린다. */
@Tag("mysql")
class ConnectorActionExecutionMysqlTest extends ConnectorActionExecutionTest {
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
