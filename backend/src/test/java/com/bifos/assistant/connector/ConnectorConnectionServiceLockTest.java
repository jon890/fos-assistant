package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorProperties;
import com.bifos.assistant.connector.application.ConnectorBindingInstalls;
import com.bifos.assistant.connector.application.ConnectorBindingApplier;
import com.bifos.assistant.connector.application.ConnectorInstallAttempts;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorBindingAttempt;
import com.bifos.assistant.connector.application.model.ConnectorInstallResult;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.hermes.ConnectorInstallConflict;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.application.SignInRevocation;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 실제 서비스와 DB에서 설치 시작의 커밋과 늦은 응답의 CAS를 본다. 대시보드 호출만 공용 mock으로 멈춘다. */
class ConnectorConnectionServiceLockTest extends ConnectorBindingServiceLockTest {
    private static final InstallResult INSTALLED = new InstallResult(false, false);
    private static final ConnectorInstallResult READY = new ConnectorInstallResult(INSTALLED, false, ResyncOutcome.READY, 0, false);

    @Autowired
    ConnectorInstallAttempts attempts;

    @Autowired
    ConnectorBindingInstalls installs;

    @Autowired
    SignInRevocation access;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    DataSource dataSource;

    @Autowired
    TestClock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ConnectorActionService approvals;

    @Autowired
    BrowserGatewayTokens tokens;

    @Autowired
    ConnectorBindingApplier applier;

    private ConnectorConnectionService requests;


    /** 공용 컨텍스트를 나누지 않고 기존 서비스 생성자에 동시 호출 두 자리를 주어 실제 DB 경쟁을 만든다. */
    @BeforeEach
    void configureConcurrentRequests() {
        requests = new ConnectorConnectionService(connections, bindings, users, service, connector,
            transactionManager, new ConnectorCallLimiter(new ConnectorProperties(2, 1000), clock), approvals, tokens, clock);
    }


    @AfterEach
    void cleanAccess() {
        people.deleteAll();
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 고정 시계의 두 재등록은 서로 다른 시도와 revision을 커밋하고 늦은 성공과 실패가 새 설치를 덮지 못한다")
    void discardsEarlierRegistrationAfterNextRegistration(boolean lateFailure) throws Exception {
        clock.set(Instant.parse("2026-10-11T00:00:00Z"));
        Target target = readyTarget();
        Observed before = observe(target.bindingId());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenAnswer(invocation -> {
                assertNoTransaction();
                Observed started = observe(target.bindingId());
                assertThat(started.revision()).isAfter(before.revision());
                assertThat(started.attempt()).isNotNull();
                assertThat(started.status()).isEqualTo("PENDING");
                assertThat(started.desired()).isFalse();
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                    if (lateFailure) {
                        throw new IllegalStateException("lost response");
                    }
                    return new InstallResult(true, false);
                }
                return new InstallResult(false, false, true);
            });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> requests.register(target.owner(), DEMO, VALUES));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            UUID firstId = observe(target.bindingId()).attempt();
            pool.submit(() -> requests.register(target.owner(), DEMO, Map.of("token", "demo_next_0123456789")))
                .get(10, TimeUnit.SECONDS);
            Observed current = observe(target.bindingId());
            assertThat(current.attempt()).isNull();
            assertThat(firstId).isNotNull();
            assertThat(current.revision()).isAfter(before.revision());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(observe(target.bindingId())).isEqualTo(current);
            assertThat(bindings.findById(target.bindingId()).orElseThrow().restartRequired()).isFalse();
            assertThat(bindings.findById(target.bindingId()).orElseThrow().applyDueAt()).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    @Test
    @DisplayName("check의 probe가 멈춘 동안 해제가 완료되고 늦은 성공이 삭제한 바인딩을 되살리지 않는다")
    void completesDisconnectWhileProbeWaits() throws Exception {
        Target target = readyTarget();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connector.callWithVault(anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(CallResult.success(JsonMapper.builder().build().readTree("{}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenAnswer(invocation -> {
                assertNoTransaction();
                return INSTALLED;
            });
        when(connector.readConnector(anyString(), anyString())).thenAnswer(invocation -> {
            assertNoTransaction();
            return new ConnectorState("p", true, true, false, true, "bind");
        });
        when(connector.probe(anyString(), anyString())).thenAnswer(invocation -> {
            assertNoTransaction();
            assertThat(observe(target.bindingId()).attempt()).isNotNull();
            entered.countDown();
            await(release);
            return new ProbeResult(true, List.of("list_scopes"));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> checking = pool.submit(() -> requests.check(target.owner(), DEMO));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> connectionService.disconnect(target.owner(), DEMO)).get(10, TimeUnit.SECONDS);
            release.countDown();
            checking.get(10, TimeUnit.SECONDS);
            assertThat(bindings.findById(target.bindingId())).isEmpty();
            assertThat(connections.findByUserIdAndConnectorId(target.owner().id(), DEMO).orElseThrow().status())
                .isEqualTo(ConnectionStatus.DISCONNECTED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    @Test
    @DisplayName("verify 중 바뀐 계정의 PENDING을 이전 계정의 성공으로 READY로 올리지 않는다")
    void discardsVerifyForReplacedAccount() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        requests.register(owner, DEMO, VALUES);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connector.callWithVault(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(invocation -> {
            assertNoTransaction();
            entered.countDown();
            await(release);
            return CallResult.success(JsonMapper.builder().build().readTree("{}"));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> checking = pool.submit(() -> requests.check(owner, DEMO));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> {
                requests.register(owner, DEMO, Map.of("token", "demo_next_0123456789"));
                transactions.executeWithoutResult(status -> {
                    users.findByIdForUpdate(owner.id()).orElseThrow();
                    connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow().pending(clock.instant());
                });
            }).get(10, TimeUnit.SECONDS);
            Instant revision = connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow().updatedAt();
            release.countDown();
            checking.get(10, TimeUnit.SECONDS);
            assertThat(connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow().status())
                .isEqualTo(ConnectionStatus.PENDING);
            assertThat(connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow().updatedAt()).isEqualTo(revision);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    @Test
    @DisplayName("설치 응답을 기다리는 동안 접근 철회가 완료되고 결과는 FORBIDDEN이며 PENDING이 남는다")
    void refusesResultAfterAccessRevocation() throws Exception {
        Target target = readyTarget();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenAnswer(invocation -> {
                assertNoTransaction();
                entered.countDown();
                await(release);
                return INSTALLED;
            });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> registering = pool.submit(() -> requests.register(target.owner(), DEMO, VALUES));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> transactions.executeWithoutResult(status -> {
                users.findByIdForUpdate(target.owner().id()).orElseThrow();
                AllowedPerson person = people.save(AllowedPerson.of(target.owner().email(), "검사", "revoked-" + target.owner().id(), clock.instant()));
                person.disable();
            })).get(10, TimeUnit.SECONDS);
            release.countDown();
            assertThatThrownBy(() -> registering.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(observe(target.bindingId()).status()).isEqualTo("PENDING");
            assertThat(observe(target.bindingId()).attempt()).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    @Test
    @DisplayName("서비스를 다시 만든 뒤에도 같은 시각의 새 시도만 적용하고 중복 결과를 버리며 연결 revision은 유지한다")
    void consumesCurrentAttemptOnceAcrossServiceRecreation() throws Exception {
        clock.set(Instant.parse("2026-10-11T00:00:00Z"));
        Target target = readyTarget();
        ConnectorBindingAttempt first = capture(target);
        ConnectorBindingAttempt second = capture(target);
        assertThat(first.attemptId()).isNotEqualTo(second.attemptId());
        assertThat(first.startedAt()).isEqualTo(second.startedAt());
        ConnectorInstallAttempts recreated = new ConnectorInstallAttempts(bindings, agents, users, installs, access, transactions);
        assertThat(recreated.complete(first, READY)).isEmpty();
        assertThat(recreated.complete(second, READY)).contains(ResyncOutcome.READY);
        assertThat(recreated.complete(second, READY)).isEmpty();
        assertThat(observe(target.bindingId()).revision()).isEqualTo(second.connectionRevision());
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
        assertThat(observe(target.bindingId()).attempt()).isNull();
    }


    @Test
    @DisplayName("결과 저장 트랜잭션이 실패하면 시작의 PENDING과 시도가 남고 다음 연결 확인이 복구한다")
    void preservesPendingAfterResultCommitFails() throws Exception {
        Target target = readyTarget();
        ConnectorBindingAttempt attempt = capture(target);
        TransactionTemplate failing = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) throws TransactionException {
                return super.execute(status -> {
                    action.doInTransaction(status);
                    throw new IllegalStateException("result commit failed");
                });
            }
        };
        ConnectorInstallAttempts failed = new ConnectorInstallAttempts(bindings, agents, users, installs, access, failing);
        assertThatThrownBy(() -> failed.complete(attempt, READY)).isInstanceOf(IllegalStateException.class);
        assertThat(observe(target.bindingId()).attempt()).isEqualTo(attempt.attemptId());
        assertThat(observe(target.bindingId()).status()).isEqualTo("PENDING");
        allowCheck();
        requests.check(target.owner(), DEMO);
        assertThat(observe(target.bindingId()).attempt()).isNull();
        assertThat(observe(target.bindingId()).status()).isEqualTo("READY");
    }


    @ParameterizedTest
    @ValueSource(strings = {"hermes_profile", "credential_scope", "owner_user_id"})
    @DisplayName("결과의 profile과 credential과 소유자가 현재 값과 다르면 READY로 올리지 않는다")
    void discardsChangedInstallContext(String column) throws Exception {
        Target target = readyTarget();
        ConnectorBindingAttempt attempt = capture(target);
        if (column.equals("hermes_profile")) {
            jdbc.update("UPDATE agent SET hermes_profile = ? WHERE id = ?", "changed-" + target.agent().id(), target.agent().id());
        } else if (column.equals("credential_scope")) {
            jdbc.update("UPDATE agent SET credential_scope = 'DEDICATED' WHERE id = ?", target.agent().id());
        } else {
            CurrentUser next = user(UserRole.MEMBER, 1L);
            jdbc.update("UPDATE agent SET owner_user_id = ? WHERE id = ?", next.id(), target.agent().id());
        }
        assertThat(attempts.complete(attempt, READY)).isEmpty();
        assertThat(observe(target.bindingId()).status()).isEqualTo("PENDING");
    }


    @Test
    @DisplayName("새 붙이기를 해제하고 다시 붙인 뒤 이전 거절이 새 바인딩을 지우지 않는다")
    void preservesNewBindingAfterEarlierBindIsRejected() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        requests.register(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(invocation -> {
            assertNoTransaction();
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                throw new ConnectorInstallConflict();
            }
            return INSTALLED;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.bind(owner, agent.code(), DEMO));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Long old = bindings.findByAgentId(agent.id()).getFirst().id();
            pool.submit(() -> {
                service.unbind(owner, agent.code(), DEMO);
                service.bind(owner, agent.code(), DEMO);
            }).get(10, TimeUnit.SECONDS);
            Long current = bindings.findByAgentId(agent.id()).getFirst().id();
            assertThat(current).isNotEqualTo(old);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(bindings.findByAgentId(agent.id())).extracting(binding -> binding.id()).containsExactly(current);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"confirm", "scheduled", "drift"})
    @DisplayName("관리자 확인과 예약 반영과 어긋남 재설치의 HTTP도 트랜잭션 밖에서 커밋된 시도를 본다")
    void observesCommittedAttemptOutsideTransaction(String entry) throws Exception {
        Target target = readyTarget();
        when(connector.readCatalog()).thenAnswer(invocation -> {
            assertNoTransaction();
            return List.of(DEMO_MANIFEST);
        });
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenAnswer(invocation -> {
                assertNoTransaction();
                Observed observed = observe(target.bindingId());
                assertThat(observed.attempt()).isNotNull();
                assertThat(observed.status()).isEqualTo("PENDING");
                assertThat(observed.desired()).isFalse();
                return INSTALLED;
            });
        AtomicInteger reads = new AtomicInteger();
        when(connector.readConnector(anyString(), anyString())).thenAnswer(invocation -> {
            assertNoTransaction();
            boolean configured = !entry.equals("drift") || reads.incrementAndGet() > 1;
            return new ConnectorState("p", configured, true, false, true, "bind");
        });
        when(connector.probe(anyString(), anyString())).thenAnswer(invocation -> {
            assertNoTransaction();
            return new ProbeResult(true, List.of("list_scopes"));
        });
        if (entry.equals("confirm")) {
            service.confirmApplied(user(UserRole.ADMIN, 1L), target.agent().code(), DEMO, null);
        } else if (entry.equals("scheduled")) {
            transactions.executeWithoutResult(status -> {
                ConnectorBinding binding = bindings.findById(target.bindingId()).orElseThrow();
                binding.pending(clock.instant());
                binding.scheduleApply(clock.instant().minusSeconds(1));
                bindings.save(binding);
            });
            assertThat(applier.applyDue()).isEqualTo(1);
        } else {
            assertThat(applier.resyncDrifted()).isEqualTo(1);
        }
        Observed applied = observe(target.bindingId());
        assertThat(applied.status()).isEqualTo("READY");
        assertThat(applied.attempt()).isNull();
    }


    @Test
    @DisplayName("설치를 보내지 않는 재시작 대기 확인은 기존의 켜려는 의도를 보존한다")
    void preservesInstallIntentWhileRestartIsPending() {
        Target target = readyTarget();
        jdbc.update("UPDATE agent_connector_binding SET restart_required = TRUE WHERE id = ?", target.bindingId());
        ConnectorBindingAttempt waiting = capture(target);
        assertThat(waiting.waiting()).isEqualTo(ResyncOutcome.RESTART_PENDING);
        assertThat(attempts.execute(waiting)).contains(ResyncOutcome.RESTART_PENDING);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().desiredEnabled()).isTrue();
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("옛 바인딩은 설치 전 조회 실패와 꺼진 설치 뒤에도 의도를 보존하고 다음 확인으로 복구한다")
    void preservesLegacyIntentBeforeInstall(boolean readFailure) throws Exception {
        Target target = readyTarget();
        transactions.executeWithoutResult(status -> agents.findById(target.agent().id()).orElseThrow().markConnectorManaged());
        allowCheck();
        AtomicInteger installsSent = new AtomicInteger();
        doAnswer(invocation -> {
                assertNoTransaction();
                assertThat(observe(target.bindingId()).desired()).isFalse();
                installsSent.incrementAndGet();
                return INSTALLED;
            }).when(connector).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        when(connector.readConnector(anyString(), anyString())).thenAnswer(invocation -> {
            assertNoTransaction();
            if (readFailure) {
                throw new IllegalStateException("installation read failed");
            }
            return new ConnectorState("p", false, true, false, true, "legacy");
        });
        if (readFailure) {
            assertThatThrownBy(() -> requests.check(target.owner(), DEMO)).isInstanceOf(ConnectorOperationFailure.class);
        } else {
            requests.check(target.owner(), DEMO);
        }
        assertThat(observe(target.bindingId()).desired()).isTrue();
        assertThat(observe(target.bindingId()).status()).isEqualTo("PENDING");
        assertThat(installsSent).hasValue(0);
        doReturn(new ConnectorState("p", true, true, false, true, "legacy"))
            .when(connector).readConnector(anyString(), anyString());
        requests.check(target.owner(), DEMO);
        assertThat(installsSent).hasValue(1);
        assertThat(observe(target.bindingId()).status()).isEqualTo("READY");
        assertThat(observe(target.bindingId()).desired()).isTrue();
    }


    @Test
    @DisplayName("옛 READY 바인딩의 꺼진 설치를 드리프트가 보아도 의도를 보존하고 다음 확인이 복구한다")
    void preservesLegacyIntentAfterDrift() {
        Target target = readyTarget();
        transactions.executeWithoutResult(status -> agents.findById(target.agent().id()).orElseThrow().markConnectorManaged());
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", false, false, false, true, "legacy"));
        assertThat(applier.resyncDrifted()).isEqualTo(1);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().desiredEnabled()).isTrue();
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.PENDING);
        allowCheck();
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", true, true, false, true, "legacy"));
        requests.check(target.owner(), DEMO);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
    }


    @Test
    @DisplayName("옛 바인딩의 설치 요청 자체가 실패하면 낮춘 의도를 유지한다")
    void keepsLegacyDisabledAfterInstallWasSent() {
        Target target = readyTarget();
        transactions.executeWithoutResult(status -> agents.findById(target.agent().id()).orElseThrow().markConnectorManaged());
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
            .thenThrow(new IllegalStateException("install response lost"));
        assertThat(attempts.execute(capture(target))).contains(ResyncOutcome.CALL_FAILED);
        assertThat(bindings.findById(target.bindingId()).orElseThrow().desiredEnabled()).isFalse();
        assertThat(bindings.findById(target.bindingId()).orElseThrow().status()).isEqualTo(BindingStatus.PENDING);
    }


    @ParameterizedTest
    @ValueSource(strings = {"confirm", "scheduled", "drift"})
    @DisplayName("철회된 주인의 관리자 확인과 예약과 드리프트는 설치 시작과 외부 설치를 보내지 않는다")
    void refusesInstallStartForRevokedOwner(String entry) throws Exception {
        Target target = readyTarget();
        if (entry.equals("scheduled")) {
            transactions.executeWithoutResult(status -> {
                ConnectorBinding binding = bindings.findById(target.bindingId()).orElseThrow();
                binding.pending(clock.instant());
                binding.scheduleApply(clock.instant().minusSeconds(1));
            });
        }
        Observed before = observe(target.bindingId());
        transactions.executeWithoutResult(status -> {
            users.findByIdForUpdate(target.owner().id()).orElseThrow();
            AllowedPerson person = people.save(AllowedPerson.of(target.owner().email(), "검사", "revoked-" + target.owner().id(), clock.instant()));
            person.disable();
        });
        clearInvocations(connector);
        when(connector.readConnector(anyString(), anyString()))
            .thenReturn(new ConnectorState("p", false, false, false, true, "bind"));
        if (entry.equals("confirm")) {
            assertThatThrownBy(() -> service.confirmApplied(user(UserRole.ADMIN, 1L), target.agent().code(), DEMO, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        } else if (entry.equals("scheduled")) {
            assertThat(applier.applyDue()).isZero();
            assertThat(bindings.findById(target.bindingId()).orElseThrow().applyDueAt()).isNotNull();
        } else {
            assertThat(applier.resyncDrifted()).isZero();
        }
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(observe(target.bindingId())).isEqualTo(before);
    }


    private Target readyTarget() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        requests.register(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        Long bindingId = bindings.findByAgentId(agent.id()).getFirst().id();
        jdbc.update("UPDATE agent_connector_binding SET status = 'READY', restart_required = FALSE, restart_required_since = NULL WHERE id = ?", bindingId);
        return new Target(owner, agent, bindingId);
    }


    private ConnectorBindingAttempt capture(Target target) {
        return attempts.start(() -> {
            attempts.lockOwner(target.owner().id());
            agents.findByIdForUpdate(target.agent().id()).orElseThrow();
            return attempts.begin(bindings.findById(target.bindingId()).orElseThrow(), Optional.of(DEMO_MANIFEST), Map.of(), true, false, false, clock.instant());
        });
    }


    private void allowCheck() {
        when(connector.callWithVault(anyString(), anyString(), anyString(), nullable(String.class)))
            .thenReturn(CallResult.success(JsonMapper.builder().build().readTree("{}")));
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class))).thenReturn(INSTALLED);
    }


    /** 호출 스레드와 별도 DB 연결로 커밋된 시도와 revision을 직접 읽는다. */
    private Observed observe(Long bindingId) throws Exception {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement query = connection.prepareStatement("SELECT c.updated_at, b.install_attempt_id, b.status, b.desired_enabled FROM agent_connector_binding b JOIN connector_connection c ON c.id = b.connection_id WHERE b.id = ?")) {
            query.setLong(1, bindingId);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                byte[] raw = row.getBytes(2);
                ByteBuffer bytes = raw == null ? null : ByteBuffer.wrap(raw);
                UUID attempt = bytes == null ? null : new UUID(bytes.getLong(), bytes.getLong());
                return new Observed(row.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(),
                    attempt, row.getString(3), row.getBoolean(4));
            }
        }
    }


    private static void assertNoTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }


    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }


    private record Target(CurrentUser owner, Agent agent, Long bindingId) {
    }

    private record Observed(Instant revision, UUID attempt, String status, boolean desired) {
    }
}
