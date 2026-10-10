package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionClaimCodec;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionClaims;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionCurrent;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionTicket;
import com.bifos.assistant.connector.application.model.ConnectorExecutionClaimInput;
import com.bifos.assistant.connector.application.model.ConnectorExecutionTicketPayload;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.HermesToolName;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.ConnectorExecutionClaimTestSupport;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 실제 HTTP와 별도 DB 연결로 인증, 소비, HTTP 중 철회를 관측한다. */
class ConnectorExecutionClaimTest extends ConnectorExecutionClaimTestSupport {
    static final JsonNode VECTOR = vectors();

    @Autowired
    ConnectorExecutionTicket tickets;

    @Autowired
    ConnectorExecutionClaims claims;

    @Autowired
    ConnectorExecutionCurrent current;

    @Autowired
    HermesProperties properties;

    @Autowired
    ConnectorExecutionClaimCodec codec;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorActionExecutionRepository contents;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentExecutionRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    TextCipher cipher;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("MICROS 발급 값을 DB에서 다시 읽고 한 번 소비한 권한은 재사용할 수 없다")
    void issuesAndConsumesExactlyOnce() {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        var payload = tickets.authenticate(raw);
        assertThat(payload.issuedAt()).isEqualTo(NOW);
        assertThat(payload.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(row(f).ticketExpiresAt()).isEqualTo(payload.expiresAt());
        assertThatThrownBy(() -> tickets.issue(f.action())).isInstanceOf(ApiException.class);
        assertThat(claims.claim(input(raw)).allowed()).isTrue();
        assertThat(row(f).consumedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("별도 연결의 병렬 발급과 claim은 각각 한 요청만 커밋한다")
    void serializesParallelRequests(boolean consume) throws Exception {
        Fixture f = fixture();
        String raw = consume ? tickets.issue(f.action()) : null;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = IntStream.range(0, 6)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        try {
                            if (consume) {
                                claims.claim(input(raw));
                            } else {
                                tickets.issue(f.action());
                            }
                            return 1;
                        } catch (ApiException ignored) {
                            return 0;
                        }
                    }))
                    .toList();
            start.countDown();
            int succeeded = 0;
            for (var job : jobs) {
                succeeded += job.get(15, TimeUnit.SECONDS);
            }
            assertThat(succeeded).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,register", "false,disconnect", "false,unbind", "true,register", "true,disconnect", "true,unbind"
    })
    @DisplayName("카탈로그 HTTP 중에도 실행 중인 승인의 실제 등록·해제·떼기를 기존 정책으로 거절한다")
    void rejectsWithdrawalBeforeHttpRelease(boolean consume, String mutation) throws Exception {
        Fixture f = fixture();
        String raw = consume ? tickets.issue(f.action()) : null;
        ConnectorActionExecution before = row(f);
        Barrier held = new Barrier();
        BARRIER.set(held);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> {
                try {
                    if (consume) {
                        claims.claim(input(raw));
                    } else {
                        tickets.issue(f.action());
                    }
                    return true;
                } catch (ApiException ignored) {
                    return false;
                }
            });
            assertThat(held.entered.await(3, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(1));
            var changed = pool.submit(() -> {
                assertThatThrownBy(() -> {
                            switch (mutation) {
                                case "register" ->
                                    connectionService.register(f.user(), "demo-financial", Map.of("account", "000008"));
                                case "disconnect" -> connectionService.disconnect(f.user(), "demo-financial");
                                case "unbind" -> bindingService.unbind(f.user(), f.agentCode(), "demo-financial");
                                default -> throw new IllegalArgumentException(mutation);
                            }
                        })
                        .isInstanceOf(ApiException.class)
                        .extracting("code")
                        .isEqualTo(ErrorCode.CONNECTOR_ACTION_EXECUTING);
            });
            changed.get(3, TimeUnit.SECONDS);
            tx().executeWithoutResult(status -> {
                var c = connections.findById(f.connection()).orElseThrow();
                var b = bindings.findById(f.binding()).orElseThrow();
                assertThat(c.fields().values()).containsEntry("account", "000007");
                assertThat(c.updatedAt()).isEqualTo(before.connectionUpdatedAt());
                assertThat(b.updatedAt()).isEqualTo(before.bindingUpdatedAt());
                assertThat(b.desiredEnabled()).isTrue();
                assertThat(b.status().name()).isEqualTo("READY");
            });
            held.release.countDown();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(row(f).ticketId()).isNotNull();
            if (consume) {
                assertThat(row(f).consumedAt()).isNotNull();
            }
        } finally {
            held.release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,expiry", "true,expiry", "false,failure", "true,failure", "false,timeout", "true,timeout"})
    @DisplayName("HTTP 대기 중 만료와 실제 실패·timeout은 권한 상태를 바꾸지 않는다")
    void preservesStateOnHttpExpiryAndFailures(boolean consume, String reason) throws Exception {
        Fixture f = fixture();
        String raw = consume ? tickets.issue(f.action()) : null;
        var before = row(f);
        Barrier held = new Barrier();
        if (!"failure".equals(reason)) {
            BARRIER.set(held);
        } else {
            FAIL_CATALOG.set(true);
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> {
                assertThatThrownBy(() -> {
                            if (consume) {
                                claims.claim(input(raw));
                            } else {
                                tickets.issue(f.action());
                            }
                        })
                        .isInstanceOf(ApiException.class);
            });
            if (!"failure".equals(reason)) {
                assertThat(held.entered.await(3, TimeUnit.SECONDS)).isTrue();
                if ("expiry".equals(reason)) {
                    clock.set(consume ? NOW.plusSeconds(60) : NOW.plusSeconds(300));
                    held.release.countDown();
                }
            }
            pending.get(8, TimeUnit.SECONDS);
            assertUnchanged(f, before);
        } finally {
            held.release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("HTTP 중 실제 반영 완료의 PENDING 커밋이 먼저 보이고 옛 발급·claim 맥락은 거절된다")
    void rejectsContextChangedByActualConfirmApplied(boolean consume) throws Exception {
        Fixture f = fixture();
        String raw = consume ? tickets.issue(f.action()) : null;
        var before = row(f);
        Barrier held = new Barrier();
        BARRIER.set(held);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> {
                assertThatThrownBy(() -> {
                            if (consume) {
                                claims.claim(input(raw));
                            } else {
                                tickets.issue(f.action());
                            }
                        })
                        .isInstanceOf(ApiException.class);
            });
            assertThat(held.entered.await(3, TimeUnit.SECONDS)).isTrue();
            CONFIGURED.set(false);
            var admin = new CurrentUser(f.user().id(), f.user().email(), "검사 관리자", 1L, UserRole.ADMIN);
            var changed = pool.submit(() -> assertThatThrownBy(
                            () -> bindingService.confirmApplied(admin, f.agentCode(), "demo-financial", null))
                    .isInstanceOf(ApiException.class));
            changed.get(3, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject(
                            "select status from agent_connector_binding where id = ?", String.class, f.binding()))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                                    "select updated_at from agent_connector_binding where id = ?",
                                    Timestamp.class,
                                    f.binding())
                            .toInstant())
                    .isNotEqualTo(before.bindingUpdatedAt());
            held.release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertUnchanged(f, before);
        } finally {
            held.release.countDown();
        }
    }

    @Test
    @DisplayName("외부 트랜잭션과 잘못된 서명은 카탈로그와 상태 변경 전에 거절한다")
    void rejectsCallerTransactionAndUnauthenticatedRequest() {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        int requests = CATALOG_REQUESTS.get();
        tx().executeWithoutResult(status -> {
            assertThatThrownBy(() -> tickets.issue(f.action())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
        });
        String wrong = raw.substring(0, raw.indexOf('.') + 1)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertThatThrownBy(() -> claims.claim(input(wrong)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(CATALOG_REQUESTS.get()).isEqualTo(requests);
        assertThat(row(f).consumedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("flush 뒤 실제 JDBC 연결의 commit 실패에서는 원문이나 허용 결과가 반환되지 않는다")
    void blocksResponseOnActualCommitFailure(boolean consume) {
        Fixture f = fixture();
        String raw = consume ? tickets.issue(f.action()) : null;
        var before = row(f);
        var armed = new AtomicBoolean(true);
        PlatformTransactionManager failing = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                TransactionStatus result = manager.getTransaction(definition);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        armed.set(false);
                        em.flush();
                        em.unwrap(Session.class).doWork(connection -> connection.close());
                    }
                });
                return result;
            }

            @Override
            public void commit(TransactionStatus status) {
                manager.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
        };
        var issuing =
                new ConnectorExecutionTicket(current, codec, actions, contents, users, failing, clock, properties);
        var consuming = new ConnectorExecutionClaims(tickets, codec, current, users, actions, contents, failing, clock);
        assertThatThrownBy(() -> {
                    if (consume) {
                        consuming.claim(input(raw));
                    } else {
                        issuing.issue(f.action());
                    }
                })
                .isInstanceOf(ApiException.class);
        assertThat(armed).isFalse();
        assertUnchanged(f, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"protocol", "risk", "scope", "missing", "unknown"})
    @DisplayName("현재 보호 계약 철회와 UNKNOWN 상태는 발급·소비를 거절한다")
    void rejectsCurrentGuardWithdrawalAndUnknownState(String reason) {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        switch (reason) {
            case "protocol" -> CATALOG_BODY.set(CATALOG.replace("approval-claim-v1", "approval-claim-v2"));
            case "risk" -> CATALOG_BODY.set(CATALOG.replace("FINANCIAL", "WRITE"));
            case "scope" -> CATALOG_BODY.set(CATALOG.replace("\"field\":\"account\"", "\"field\":\"secret\""));
            case "missing" -> CATALOG_BODY.set("[]");
            case "unknown" ->
                tx().executeWithoutResult(status ->
                        actions.findByPublicId(f.action()).orElseThrow().unknown(NOW));
            default -> throw new IllegalArgumentException(reason);
        }
        assertThatThrownBy(() -> tickets.issue(f.action())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
        assertThat(row(f).consumedAt()).isNull();
    }

    @Test
    @DisplayName("권한 payload의 지수·overflow·중복·추가 키·시각 표현을 strict하게 거절한다")
    void rejectsNoncanonicalPayloadAndRequest() {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        String body = new String(Base64.getUrlDecoder().decode(raw.split("\\.")[0]), StandardCharsets.UTF_8);
        for (String invalid : new String[] {
            body.replace("\"v\":1", "\"v\":1e0"),
            body.replace("\"v\":1", "\"v\":1,\"v\":1"),
            body + "{}",
            body.replace("\"v\":1", "\"v\":true"),
            body.replace("\"v\":1", "\"v\":9223372036854775808"),
            body.replace(".123456Z", ".1234560Z"),
            body.replace("\"v\":1", "\"v\":1,\"extra\":0")
        }) {
            assertThatThrownBy(() -> tickets.authenticate(signed(invalid)))
                    .isInstanceOf(ApiException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> tickets.authenticate(raw + "=")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.decodeRequest("{\"tool\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        String request = JSON.writeValueAsString(input(raw));
        assertThatThrownBy(() -> codec.decodeRequest((request + "{}").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.decodeRequest(
                        request.replace("\"scope\":{", "\"scope\":{\"pad\":\"" + "x".repeat(2048) + "\",")
                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("응답 유실 뒤 새 codec과 서비스도 DB의 발급·소비 상태를 재사용할 수 없다")
    void rejectsReuseAcrossFreshServiceInstances() {
        Fixture f = fixture();
        String raw = tickets.issue(f.action());
        var freshCodec = new ConnectorExecutionClaimCodec();
        var freshTickets =
                new ConnectorExecutionTicket(current, freshCodec, actions, contents, users, manager, clock, properties);
        var freshClaims = new ConnectorExecutionClaims(
                freshTickets, freshCodec, current, users, actions, contents, manager, clock);
        assertThatThrownBy(() -> freshTickets.issue(f.action())).isInstanceOf(ApiException.class);
        assertThat(freshClaims.claim(input(raw)).allowed()).isTrue();
        assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
        assertThat(row(f).consumedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("서명이 맞아도 미발급 ticket과 미래 시각, 만료 경계와 다른 scope를 거절한다")
    void rejectsUnissuedFutureExpiredAndScopeMismatch() {
        Fixture f = fixture();
        var context = current.readContext(f.action());
        var stored = row(f);
        var unissued = new ConnectorExecutionTicketPayload(
                1,
                UUID.randomUUID(),
                f.action(),
                f.user().id(),
                context.agentId(),
                f.connection(),
                f.binding(),
                context.profile(),
                "demo-financial",
                "place_order",
                stored.executionArgsSha256(),
                stored.scopeSha256(),
                NOW,
                NOW.plusSeconds(60));
        assertThatThrownBy(() -> claims.claim(input(signed(JSON.writeValueAsString(unissued)))))
                .isInstanceOf(ApiException.class);
        assertThat(row(f).ticketId()).isNull();
        String raw = tickets.issue(f.action());
        var issued = tickets.authenticate(raw);
        assertThatThrownBy(() -> claims.claim(new ConnectorExecutionClaimInput(
                        raw, issued.tool(), issued.argsSha256(), Map.of("account_seq", "000008"))))
                .isInstanceOf(ApiException.class);
        clock.set(NOW.minusNanos(1000));
        assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
        clock.set(issued.expiresAt());
        assertThatThrownBy(() -> claims.claim(input(raw))).isInstanceOf(ApiException.class);
        assertThat(row(f).consumedAt()).isNull();
    }

    @Test
    @DisplayName("잘못된 UTF-8과 payload·본문·scope의 독립 바이트 상한을 거절한다")
    void rejectsMalformedUtf8AndIndependentByteLimits() {
        assertThatThrownBy(() -> codec.decodeRequest(new byte[] {'{', (byte) 0xc0, (byte) 0xaf, '}'}))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.decodePayload(new byte[3073])).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.decodeRequest(new byte[8193])).isInstanceOf(ApiException.class);
        Fixture f = fixture();
        String request = JSON.writeValueAsString(input(tickets.issue(f.action())));
        String oversizedScope = request.replace("\"scope\":{", "\"scope\":{ " + " ".repeat(2048));
        assertThat(oversizedScope.getBytes(StandardCharsets.UTF_8).length).isLessThan(8192);
        assertThatThrownBy(() -> codec.decodeRequest(oversizedScope.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
        String oversizedValue = request.replace("000007", "가".repeat(43));
        assertThatThrownBy(() -> codec.decodeRequest(oversizedValue.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
    }

    Fixture fixture() {
        return tx().execute(status -> {
            String id = UUID.randomUUID().toString();
            var user = users.saveAndFlush(AppUser.of(id + "@example.test", "검사 주인", 1L, UserRole.MEMBER, NOW));
            String profile = "claim-" + id;
            people.saveAndFlush(AllowedPerson.of(user.email(), "검사 주인", profile, NOW));
            var agent = agents.saveAndFlush(Agent.of(
                    "claim-" + id,
                    "검사",
                    profile,
                    "http://localhost",
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    user.id(),
                    NOW));
            var connection = ConnectorConnection.pending(user.id(), "demo-financial", NOW);
            connection.connected(new ConnectionFields(Map.of("account", "000007"), Map.of()), NOW);
            connection.ready(NOW);
            connections.saveAndFlush(connection);
            var binding = ConnectorBinding.pending(agent, connection, "demo", NOW);
            binding.installed(false, NOW);
            binding.ready(NOW);
            bindings.saveAndFlush(binding);
            var origin = runs.saveAndFlush(AgentExecution.builder()
                    .userId(user.id())
                    .agentId(agent.id())
                    .profileName(profile)
                    .hermesSessionId("fos-" + id)
                    .costMode(CostMode.SUBSCRIPTION)
                    .status(ExecutionStatus.RUNNING)
                    .startedAt(NOW)
                    .build());
            String args = VECTOR.get("modelArgsJson").stringValue();
            var action = ConnectorAction.decided(
                    connection,
                    agent.id(),
                    origin,
                    HermesToolName.of("demo", "place_order"),
                    "place_order",
                    new ToolPolicyDecision(
                            ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS),
                    false,
                    Sha256.hex(id),
                    Sha256.hex(args),
                    NOW);
            action.awaitApproval(args, NOW.plusSeconds(300));
            actions.saveAndFlush(action);
            contents.saveAndFlush(ConnectorExecutionSnapshot.capture(
                    action,
                    binding,
                    "CREATE",
                    DECLARATION,
                    VECTOR.get("executionArgsJson").stringValue(),
                    VECTOR.get("summaryJson").stringValue(),
                    VECTOR.get("scopeJson").stringValue(),
                    cipher,
                    NOW));
            action.beginExecution(NOW);
            return new Fixture(
                    action.publicId(),
                    action.id(),
                    connection.id(),
                    binding.id(),
                    agent.code(),
                    new CurrentUser(user.id(), user.email(), "검사 주인", 1L, UserRole.MEMBER));
        });
    }

    ConnectorActionExecution row(Fixture fixture) {
        return tx().execute(status -> contents.findById(fixture.actionId()).orElseThrow());
    }

    ConnectorExecutionClaimInput input(String raw) {
        return new ConnectorExecutionClaimInput(
                raw,
                "place_order",
                Sha256.hex(VECTOR.get("executionArgsJson").stringValue()),
                Map.of("account_seq", "000007"));
    }

    TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private void assertUnchanged(Fixture fixture, ConnectorActionExecution before) {
        var after = row(fixture);
        assertThat(after.ticketId()).isEqualTo(before.ticketId());
        assertThat(after.ticketExpiresAt()).isEqualTo(before.ticketExpiresAt());
        assertThat(after.consumedAt()).isNull();
    }

    static String signed(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] key = mac.doFinal("fos-approval-signing-key-v1".getBytes(StandardCharsets.US_ASCII));
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String encoded =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            return encoded + "."
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(mac.doFinal(
                                    ("fos-approval-claim-v1" + encoded).getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode vectors() {
        try {
            return JSON.readTree(Files.readString(Path.of("../test/fixtures/financial-approval-v1.json")))
                    .get("cases")
                    .get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Fixture(UUID action, Long actionId, Long connection, Long binding, String agentCode, CurrentUser user) {}
}
