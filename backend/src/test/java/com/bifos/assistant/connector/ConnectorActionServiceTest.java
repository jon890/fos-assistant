package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.ConnectorPolicyTestDoubles.ChangeRecorder;
import com.bifos.assistant.connector.ConnectorPolicyTestDoubles.Seen;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorCheckReportApprovals;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorPolicyService;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.feedback.application.FeedbackConversations;
import com.bifos.assistant.hermes.ConnectorExecutionUnknown;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorRecovery;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인 줄을 만들고 승인, 거절, 만료, 상시 허락으로 다루는 흐름을 실제 DB 로 확인한다(ADR-050).
 *
 * <p>계약은 {@code docs/connectors.md} 의 「승인」 이다. 대시보드의 실행 경로는 대역이 답한다. 카탈로그는 보관 시간에
 * 걸리지 않게 검사가 시계를 보관 시간보다 멀리 옮긴다. 컨텍스트 수를 늘리지 않으려고 판정 경로의 끝단 검사와 같은
 * 구성을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ConnectorPolicyTestDoubles.class)
class ConnectorActionServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = "connector-action-owner";
    /** 같은 주인의 다른 에이전트 profile 이다. 같은 연결을 붙여 그 에이전트에서도 도구를 부른다. */
    private static final String OTHER_PROFILE = "connector-action-other";

    private static final String DEMO = "demo-notes";
    private static final String WRITE = "write_note";
    private static final String MAIL = "mail_note";
    private static final String ARGS = "{\"text\":\"안녕\",  \"count\":2}";
    private static final long CONVERSATION = 7L;

    /** Gmail 필터 id 처럼 32자 이상의 영숫자 식별자다. 선언하지 않은 칸에서는 가려진다. */
    private static final String LONG_ID = "ANe1BmhXxP8kq3Lr0sT9vUwYzA2bC4dE6fG8hJ";

    /** 화면에서 가려지는 32자 넘는 base64 모양 글이다. */
    private static final String HIDDEN_TOKEN = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5";

    /** 승인 방식이 셋 다 있고 상시 허락을 닫은 도구가 하나 있는 커넥터다. MCP 서버 이름이 {@code demo} 라 등록 이름은 {@code mcp__demo__<도구>} 다. */
    private static final ConnectorManifest DECLARING = manifest(List.of(
            new ConnectorTool("list_scopes", "READ", "none", null, null),
            new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null),
            new ConnectorTool("send_note", "WRITE", "always", null, null),
            new ConnectorTool(MAIL, "WRITE", "required", "메모 보내기", Boolean.FALSE)));

    @Autowired
    ConnectorPolicyService policies;

    @Autowired
    ConnectorActionService service;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorToolGrantRepository grants;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ChangeRecorder recorder;

    @Autowired
    DelegationProperties delegation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ConnectorCheckReportApprovals reportApprovals;

    @MockitoBean
    HermesConnectorClient connector;

    /** 이 검사의 대화 번호는 대화 줄 없이 쓰는 고정 값이라, 판단 피드백이 남아 있는 대화로 보게 한다. */
    @MockitoBean
    FeedbackConversations feedbackConversations;

    private final List<Long> createdChecks = new ArrayList<>();

    private AppUser owner;
    private CurrentUser me;
    private Agent agent;
    private AgentExecution run;
    private String root;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM decision_feedback_event");
        when(feedbackConversations.isActive(anyLong())).thenReturn(true);
        jdbc.update("DELETE FROM connector_action");
        jdbc.update("DELETE FROM connector_tool_grant");
        bindings.deleteAll();
        connections.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE, OTHER_PROFILE));
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
        recorder.seen.clear();
        // 앞선 검사가 읽은 카탈로그가 남지 않게 보관 시간보다 멀리 옮긴다.
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenReturn(List.of(DECLARING));
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.success(JSON.readTree("{\"saved\":true}")));

        owner = users.save(AppUser.of("action-owner@example.com", "주인", 1L, UserRole.MEMBER, Instant.now()));
        me = currentUser(owner);
        agent = Agent.of(
                "action-" + UUID.randomUUID(),
                "검사용 메모",
                PROFILE,
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now());
        agent.markConnectorManaged();
        agent = agents.save(agent);
        root = "fos-" + UUID.randomUUID();
        run = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .agentId(agent.id())
                .conversationId(CONVERSATION)
                .profileName(PROFILE)
                .hermesSessionId(root)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-10-01T00:00:00Z"))
                .build());
        connect(true);
    }

    @Test
    @DisplayName("보고의 남은 승인은 같은 실행 트리와 사용자의 PENDING 공개 번호만 담는다")
    void reportApprovalsUseActualPendingCardsInTheOwnedTree() {
        UUID rootPending = ask(WRITE, ARGS).actionId();
        UUID rejectedId = ask(WRITE, "{\"text\":\"다른 승인 요청\"}").actionId();
        ConnectorAction rejected = actions.findAll().stream()
                .filter(action -> action.publicId().equals(rejectedId))
                .findFirst()
                .orElseThrow();
        rejected.reject(Instant.now());
        actions.saveAndFlush(rejected);

        UUID childPending = askInWritesAllowedCheck(WRITE, ARGS).actionId();
        ProactiveCheck otherTree = checks.findById(createdChecks.getFirst()).orElseThrow();

        assertThat(reportApprovals.pendingPublicIds(run.id())).containsExactly(rootPending);
        assertThat(reportApprovals.pendingPublicIds(otherTree.rootExecutionId()))
                .containsExactly(childPending);

        jdbc.update("UPDATE connector_action SET user_id = ? WHERE origin_execution_id = ?", owner.id() + 1, run.id());
        assertThat(reportApprovals.pendingPublicIds(run.id())).isEmpty();
    }

    @Test
    @DisplayName("WRITE 도구의 정책 요청은 막고 인자 글자 그대로와 24시간 뒤 만료를 담은 PENDING 줄을 남기며 실행하지 않는다")
    void writeToolRequestIsBlockedAndStoredAsPendingWithArguments() {
        ConnectorPolicyAnswer answer = ask(WRITE, ARGS);

        ConnectorAction row = onlyAction();
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isEqualTo(row.publicId());
        assertThat(answer.message()).contains(row.publicId().toString());
        assertThat(row.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(row.passed()).isFalse();
        assertThat(row.argsJson()).isEqualTo(ARGS);
        assertThat(Duration.between(row.createdAt(), row.expiresAt())).isEqualTo(Duration.ofHours(24));
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
        // 사건은 줄을 커밋한 뒤에 나간다.
        assertThat(recorder.seen)
                .containsExactly(new Seen(new ConnectorActionChanged(CONVERSATION, row.publicId()), false));
    }

    @Test
    @DisplayName("승인 줄을 보이고 승인해 실행하면 SURFACED, APPROVED, EXECUTION_SUCCEEDED 판단 피드백이 인자 해시와 함께 남는다")
    void recordsDecisionFeedbackForApprovalFlow() {
        UUID actionId = ask(WRITE, ARGS).actionId();

        service.approve(me, actionId, null);

        ConnectorAction row = onlyAction();
        assertThat(feedbackRows())
                .containsExactly(
                        tuple("connector_action:" + actionId, "SURFACED", "AGENT", row.argsSha256()),
                        tuple("connector_action:" + actionId, "APPROVED", "USER", row.argsSha256()),
                        tuple("connector_action:" + actionId, "EXECUTION_SUCCEEDED", "SYSTEM", row.argsSha256()));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM decision_feedback_event WHERE origin_execution_id = ? AND conversation_id = ?",
                        Long.class,
                        run.id(),
                        CONVERSATION))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("승인 줄을 거절하면 REJECTED 판단 피드백이 남고 실행 결과는 남지 않는다")
    void recordsDecisionFeedbackForRejection() {
        UUID actionId = ask(WRITE, ARGS).actionId();

        service.reject(me, actionId);

        assertThat(feedbackRows()).extracting(row -> row.toList().get(1)).containsExactly("SURFACED", "REJECTED");
    }

    @Test
    @DisplayName("같은 실행이 같은 도구와 같은 인자를 다른 tool_call_id 로 다시 부르면 줄이 하나이고 같은 번호로 답한다")
    void sameToolAndArgumentsInSameRunReuseThePendingAction() {
        ConnectorPolicyAnswer first = ask(WRITE, ARGS);
        ConnectorPolicyAnswer second = ask(WRITE, ARGS);
        ConnectorPolicyAnswer otherArgs = ask(WRITE, "{\"text\":\"다른 글\"}");

        assertThat(second).isEqualTo(first);
        assertThat(otherArgs.actionId()).isNotEqualTo(first.actionId());
        assertThat(actions.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("승인하면 저장한 인자와 값이 같은 인자로 한 번 실행하고 줄이 SUCCEEDED 와 결과를 갖는다")
    void approvalExecutesOnceWithStoredArgumentsAndRecordsResult() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        recorder.seen.clear();

        ConnectorActionView approved = service.approve(me, actionId, null);

        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__write_note", ARGS);
        assertThat(JSON.readTree(onlyAction().argsJson())).isEqualTo(JSON.readTree("{\"text\":\"안녕\",\"count\":2}"));
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(approved.title()).isEqualTo("메모 쓰기");
        assertThat(approved.grantAllowed()).isTrue();
        ConnectorAction row = onlyAction();
        assertThat(row.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(JSON.readTree(row.resultText())).isEqualTo(JSON.readTree("{\"saved\":true}"));
        assertThat(row.decidedAt()).isNotNull();
        assertThat(row.executedAt()).isNotNull();
        assertThat(grants.findAll()).isEmpty();
        assertThat(recorder.seen).containsExactly(new Seen(new ConnectorActionChanged(CONVERSATION, actionId), false));
    }

    @AfterEach
    void tearDown() {
        checks.deleteAllById(createdChecks);
        createdChecks.clear();
    }

    @Test
    @DisplayName("쓰기를 허용한 살펴보기 트리의 승인 줄을 승인하면 EXECUTING 을 거쳐 저장한 인자로 커넥터 서버에 한 번 닿는다")
    void approvingActionFromWritesAllowedCheckExecutesOnce() {
        UUID actionId = askInWritesAllowedCheck(WRITE, ARGS).actionId();
        assertThat(onlyAction().status()).as("승인 전").isEqualTo(ActionStatus.PENDING);
        AtomicReference<String> statusWhileExecuting = new AtomicReference<>();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    statusWhileExecuting.set(jdbc.queryForObject("SELECT status FROM connector_action", String.class));
                    return CallResult.success(JSON.readTree("{\"saved\":true}"));
                });

        ConnectorActionView approved = service.approve(me, actionId, null);

        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__write_note", ARGS);
        assertThat(statusWhileExecuting.get()).as("실행하는 동안의 상태").isEqualTo("EXECUTING");
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("쓰기를 허용한 살펴보기 트리의 READ 와 always 승인 줄은 기간을 실은 승인이 VALIDATION_FAILED 이고 기간 없이 승인하면 한 번 실행한다")
    void grantOnAlwaysReadToolFromCheckIsRejectedAndPlainApprovalExecutes() {
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool("peek_scope", "READ", "always", "범위 엿보기", null))));
        UUID actionId = askInWritesAllowedCheck("peek_scope", ARGS).actionId();

        assertThat(actionId).as("승인 줄 번호").isNotNull();
        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::grantAllowed)
                .containsExactly(tuple(actionId, false));
        assertCode(() -> service.approve(me, actionId, GrantPeriod.HOUR), ErrorCode.VALIDATION_FAILED);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());

        assertThat(service.approve(me, actionId, null).status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__peek_scope", ARGS);
    }

    @Test
    @DisplayName("같은 승인을 두 번 누르면 둘째는 CONNECTOR_ACTION_NOT_PENDING 이고 실행은 한 번이다")
    void approvingTwiceExecutesOnce() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        service.approve(me, actionId, null);

        assertCode(() -> service.approve(me, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);

        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("두 스레드가 동시에 승인해도 실행은 한 번이고 한쪽은 CONNECTOR_ACTION_NOT_PENDING 이다")
    void concurrentApprovalsExecuteOnce() throws Exception {
        UUID actionId = ask(WRITE, ARGS).actionId();
        CountDownLatch start = new CountDownLatch(1);
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    // 실행이 도는 동안 다른 승인이 들어오게 잠시 붙든다.
                    Thread.sleep(300);
                    return CallResult.success(JSON.readTree("{\"saved\":true}"));
                });
        Callable<Object> approve = () -> {
            start.await();
            try {
                return service.approve(me, actionId, null).status();
            } catch (ApiException ex) {
                return ex.code();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(approve);
            Future<Object> second = pool.submit(approve);
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ActionStatus.SUCCEEDED, ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        } finally {
            pool.shutdownNow();
        }
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("다른 트랜잭션이 승인 줄을 잠근 동안 거절은 기다리고, 잠근 쪽이 커밋한 상태를 읽는다")
    void rowLockMakesSecondDecisionWaitForTheFirst() throws Exception {
        UUID actionId = ask(WRITE, ARGS).actionId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        ConnectorAction row =
                                actions.findByPublicIdForUpdate(actionId).orElseThrow();
                        row.beginExecution(Instant.parse("2026-10-01T00:00:00Z"));
                        actions.saveAndFlush(row);
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> rejecting = pool.submit(() -> {
                try {
                    return service.reject(me, actionId).status();
                } catch (ApiException ex) {
                    return ex.code();
                }
            });

            assertThatThrownBy(() -> rejecting.get(700, TimeUnit.MILLISECONDS))
                    .as("잠금이 풀리기 전에는 거절이 끝나지 않는다")
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(rejecting.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.EXECUTING);
    }

    @Test
    @DisplayName("실행 결과를 알 수 없으면 UNKNOWN 이고 다시 승인해도 실행하지 않는다")
    void unknownExecutionIsNotRetried() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ConnectorExecutionUnknown());

        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(approved.status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(approved.resultText()).isNull();
        assertCode(() -> service.approve(me, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.UNKNOWN);
    }

    @Test
    @DisplayName("실행 호출이 다른 예외로 끝나도 UNKNOWN 이고 예외를 밖으로 내지 않는다")
    void unexpectedExecutionFailureIsRecordedAsUnknown() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("remote detail"));

        assertThat(service.approve(me, actionId, null).status()).isEqualTo(ActionStatus.UNKNOWN);
    }

    @Test
    @DisplayName("실행이 실패 결과로 돌아오면 FAILED 와 공통 어휘의 errorCode 를 남긴다")
    void failedExecutionRecordsErrorCode() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.failure(ConnectorCallError.FORBIDDEN));

        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(approved.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(approved.errorCode()).isEqualTo("forbidden");
        assertThat(onlyAction().errorCode()).isEqualTo("forbidden");
    }

    @Test
    @DisplayName("실패 결과에 커넥터가 선언한 오류 계약이 있으면 kind 를 붙인 JSON 으로 결과 글에 남긴다")
    void failedExecutionStoresDeclaredErrorDetail() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.failure(
                        ConnectorCallError.INVALID_INPUT,
                        new ConnectorErrorDetail(
                                "GMAIL_TARGET_COUNT_CHANGED", Map.of("actual_count", 17L), ConnectorRecovery.RECHECK)));

        service.approve(me, actionId, null);

        assertThat(onlyAction().errorCode()).isEqualTo("invalid_input");
        assertThat(JSON.readTree(onlyAction().resultText()))
                .isEqualTo(JSON.readTree("{\"kind\":\"connector_error\",\"code\":\"GMAIL_TARGET_COUNT_CHANGED\","
                        + "\"details\":{\"actual_count\":17},\"recovery\":\"recheck\"}"));
    }

    @Test
    @DisplayName("결과 글이 위임 답의 상한보다 길면 상한까지만 남긴다")
    void resultLongerThanOutputLimitIsClipped() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        int max = delegation.outputMaxChars();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.success(JSON.readTree("{\"text\":\"" + "a".repeat(max) + "\"}")));

        service.approve(me, actionId, null);

        assertThat(onlyAction().resultText()).hasSize(max);
    }

    @Test
    @DisplayName("거절하면 REJECTED 이고 실행하지 않으며 그 뒤 승인은 CONNECTOR_ACTION_NOT_PENDING 이다")
    void rejectionDoesNotExecute() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        recorder.seen.clear();

        ConnectorActionView rejected = service.reject(me, actionId);

        assertThat(rejected.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(onlyAction().decidedAt()).isNotNull();
        assertCode(() -> service.approve(me, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertCode(() -> service.reject(me, actionId), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(recorder.seen).containsExactly(new Seen(new ConnectorActionChanged(CONVERSATION, actionId), false));
    }

    @Test
    @DisplayName("다른 사용자와 관리자의 승인과 거절, 없는 번호는 CONNECTOR_ACTION_NOT_FOUND 이고 줄은 PENDING 그대로다")
    void othersAndAdminsCannotDecide() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        CurrentUser other = currentUser(
                users.save(AppUser.of("action-other@example.com", "다른 사람", 1L, UserRole.MEMBER, Instant.now())));
        CurrentUser admin = currentUser(
                users.save(AppUser.of("action-admin@example.com", "관리자", 1L, UserRole.ADMIN, Instant.now())));

        assertCode(() -> service.approve(other, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertCode(() -> service.approve(admin, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertCode(() -> service.reject(admin, actionId), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertCode(() -> service.approve(me, UUID.randomUUID(), null), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(service.listForConversation(admin, CONVERSATION)).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("만료 시각이 지난 줄만 EXPIRED 가 되고 그 뒤 승인은 CONNECTOR_ACTION_NOT_PENDING 이다")
    void expireMarksOnlyActionsPastTheirExpiry() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        Instant expiresAt = onlyAction().expiresAt();
        recorder.seen.clear();

        // 만료 시각과 같은 순간은 아직 지난 것이 아니다.
        assertThat(service.expire(expiresAt)).isZero();
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(service.expire(expiresAt.plusMillis(1))).isEqualTo(1);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(service.expire(expiresAt.plusMillis(2))).isZero();
        assertCode(() -> service.approve(me, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(recorder.seen).containsExactly(new Seen(new ConnectorActionChanged(CONVERSATION, actionId), false));
    }

    @Test
    @DisplayName("만료 정리가 돌기 전이라도 만료 시각이 지난 줄을 승인하면 실행하지 않고 EXPIRED 줄을 오류 없이 돌려준다")
    void approvingAnActionPastItsExpiryExpiresIt() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        jdbc.update("UPDATE connector_action SET expires_at = ?", Instant.parse("2020-01-01T00:00:00Z"));

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.EXPIRED);
        // 이미 끝난 줄을 다시 승인하면 그때는 오류다.
        assertCode(() -> service.approve(me, actionId, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("EXECUTING 으로 남은 줄은 기동 정리가 UNKNOWN 으로 바꾸고 PENDING 은 건드리지 않는다")
    void interruptedExecutionsBecomeUnknown() {
        UUID executing = ask(WRITE, ARGS).actionId();
        UUID pending = ask(WRITE, "{\"text\":\"다른 글\"}").actionId();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ConnectorAction row = actions.findByPublicIdForUpdate(executing).orElseThrow();
            row.beginExecution(Instant.parse("2026-10-01T00:00:00Z"));
            actions.save(row);
        });

        int changed = service.markInterrupted(Instant.parse("2026-10-01T01:00:00Z"));

        assertThat(changed).isEqualTo(1);
        assertThat(actions.findAll())
                .extracting(ConnectorAction::publicId, ConnectorAction::status)
                .containsExactlyInAnyOrder(
                        tuple(executing, ActionStatus.UNKNOWN), tuple(pending, ActionStatus.PENDING));
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("HOUR 허락과 함께 승인하면 같은 도구의 새 호출은 승인 없이 통과하고 ALLOWED 줄을 남긴다")
    void grantLetsLaterCallsOfTheSameToolPass() {
        UUID actionId = ask(WRITE, ARGS).actionId();

        service.approve(me, actionId, GrantPeriod.HOUR);
        ConnectorPolicyAnswer next = ask(WRITE, "{\"text\":\"다음 글\"}");

        assertThat(next.allowed()).isTrue();
        assertThat(next.actionId()).isNull();
        ConnectorAction allowed = actions.findAll().stream()
                .filter(action -> action.status() == null)
                .findFirst()
                .orElseThrow();
        assertThat(allowed.decision()).isEqualTo(ActionDecision.ALLOWED);
        assertThat(allowed.passed()).isTrue();
        assertThat(allowed.argsJson()).isNull();
        List<ConnectorGrantView> mine = service.grants(me);
        assertThat(mine)
                .extracting(ConnectorGrantView::connectorId, ConnectorGrantView::toolName)
                .containsExactly(tuple(DEMO, WRITE));
        ConnectorToolGrant stored = grants.findAll().get(0);
        assertThat(Duration.between(stored.createdAt(), stored.expiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("허락의 기간이 지나면 같은 도구의 호출이 다시 PENDING 이 되고 허락 목록에서 빠진다")
    void expiredGrantNoLongerPasses() {
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.HOUR);
        jdbc.update("UPDATE connector_tool_grant SET expires_at = ?", Instant.parse("2020-01-01T00:00:00Z"));

        ConnectorPolicyAnswer next = ask(WRITE, "{\"text\":\"다음 글\"}");

        assertThat(next.allowed()).isFalse();
        assertThat(next.actionId()).isNotNull();
        assertThat(service.grants(me)).isEmpty();
    }

    @Test
    @DisplayName("허락을 거두면 같은 도구의 호출이 다시 PENDING 이 되고, 남의 허락을 거두려 하면 CONNECTOR_ACTION_NOT_FOUND 다")
    void revokedGrantNoLongerPasses() {
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        Long grantId = service.grants(me).get(0).grantId();
        CurrentUser other = currentUser(
                users.save(AppUser.of("action-other@example.com", "다른 사람", 1L, UserRole.ADMIN, Instant.now())));

        assertCode(() -> service.revokeGrant(other, grantId), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertCode(() -> service.revokeGrant(me, grantId + 1000), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertThat(ask(WRITE, "{\"text\":\"거두기 전\"}").allowed()).isTrue();
        service.revokeGrant(me, grantId);

        ConnectorPolicyAnswer next = ask(WRITE, "{\"text\":\"거둔 뒤\"}");
        assertThat(next.allowed()).isFalse();
        assertThat(next.actionId()).isNotNull();
        assertThat(service.grants(me)).isEmpty();
    }

    @Test
    @DisplayName("늘 승인을 받는 도구에 허락을 주려 하면 VALIDATION_FAILED 이고 줄은 PENDING 그대로이며 실행하지 않는다")
    void grantOnAlwaysToolIsRejectedAndActionStaysPending() {
        UUID actionId = ask("send_note", ARGS).actionId();

        assertCode(() -> service.approve(me, actionId, GrantPeriod.HOUR), ErrorCode.VALIDATION_FAILED);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
        // 허락 없이는 승인할 수 있고, 허락이 없으므로 다음 호출은 다시 승인을 기다린다.
        assertThat(service.approve(me, actionId, null).grantAllowed()).isFalse();
        assertThat(ask("send_note", "{\"text\":\"다음 글\"}").allowed()).isFalse();
    }

    @Test
    @DisplayName("선언이 상시 허락을 닫은 도구의 승인 줄은 grantAllowed 가 거짓이고 기간을 실은 승인은 VALIDATION_FAILED 이며 줄은 PENDING 그대로다")
    void grantOnToolWithClosedGrantIsRejectedAndActionStaysPending() {
        UUID actionId = ask(MAIL, ARGS).actionId();

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::grantAllowed)
                .containsExactly(tuple(actionId, false));
        assertCode(() -> service.approve(me, actionId, GrantPeriod.HOUR), ErrorCode.VALIDATION_FAILED);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("선언이 상시 허락을 닫은 도구도 기간 없이 승인하면 한 번 실행하고 다음 호출은 다시 승인을 기다린다")
    void toolWithClosedGrantIsApprovedEachTime() {
        UUID actionId = ask(MAIL, ARGS).actionId();

        ConnectorActionView approved = service.approve(me, actionId, null);

        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__mail_note", ARGS);
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(approved.title()).isEqualTo("메모 보내기");
        assertThat(approved.grantAllowed()).isFalse();
        assertThat(grants.findAll()).isEmpty();
        assertThat(ask(MAIL, "{\"text\":\"다음 글\"}").allowed()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("argumentsWithHiddenText")
    @DisplayName("상시 허락을 닫은 도구의 인자에 가려지는 글이 있으면 hiddenArgs 가 참이고 승인해도 실행하지 않고 REJECTED 와 hidden_args 로 끝낸다")
    void closedGrantToolWithHiddenArgumentsIsNotExecuted(String name, String args, GrantPeriod grant) {
        UUID actionId = ask(MAIL, args).actionId();
        recorder.seen.clear();

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::hiddenArgs)
                .containsExactly(tuple(actionId, true));
        ConnectorActionView closed = service.approve(me, actionId, grant);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.HIDDEN_ARGS);
        assertThat(closed.hiddenArgs()).as("끝난 줄").isFalse();
        ConnectorAction row = onlyAction();
        assertThat(row.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(row.errorCode()).isEqualTo("hidden_args");
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(recorder.seen).containsExactly(new Seen(new ConnectorActionChanged(CONVERSATION, actionId), false));
    }

    static Stream<Arguments> argumentsWithHiddenText() {
        return Stream.of(
                Arguments.of(
                        "본문 끝의 base64 모양 글",
                        "{\"body\":\"회의록입니다\\n" + HIDDEN_TOKEN + "\",\"to\":\"friend@example.com\"}",
                        null),
                Arguments.of(
                        "기간을 실은 승인",
                        "{\"body\":\"회의록입니다\\n" + HIDDEN_TOKEN + "\",\"to\":\"friend@example.com\"}",
                        GrantPeriod.HOUR),
                Arguments.of(
                        "local part 가 긴 주소", "{\"body\":\"안녕하세요\",\"to\":\"" + HIDDEN_TOKEN + "@example.com\"}", null),
                Arguments.of(
                        "password= 모양",
                        "{\"body\":\"접속할 때 password=hunter2 를 쓰세요\",\"to\":\"friend@example.com\"}",
                        null));
    }

    @Test
    @DisplayName("상시 허락을 닫은 도구라도 가려지는 글이 없는 한글 본문은 hiddenArgs 가 거짓이고 승인하면 저장한 인자로 실행한다")
    void closedGrantToolWithoutHiddenArgumentsIsExecuted() {
        String args = "{\"body\":\"안녕하세요.\\n\\\"내일\\\" 7시에 봬요 😀\\t끝\",  \"subject\":\"저녁 약속 🍜\","
                + "\"to\":\"friend@example.com\"}";
        UUID actionId = ask(MAIL, args).actionId();

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::hiddenArgs)
                .containsExactly(tuple(actionId, false));
        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__mail_note", args);
    }

    @Test
    @DisplayName("상시 허락을 닫은 도구가 식별자로 선언한 칸의 긴 id 는 가리지 않고 hiddenArgs 가 거짓이며 승인하면 실행한다")
    void declaredIdentifierIsShownAndApproved() {
        catalogBecomes(withMailIdentifiers(List.of("note_id")));
        String args = "{\"note_id\":\"" + LONG_ID + "\",\"text\":\"지울 메모\"}";
        UUID actionId = ask(MAIL, args).actionId();

        ConnectorActionView listed =
                service.listForConversation(me, CONVERSATION).getFirst();
        assertThat(listed.hiddenArgs()).isFalse();
        assertThat(listed.argsJson()).contains(LONG_ID).doesNotContain("[가림]");
        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__mail_note", args);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("argumentsHiddenDespiteIdentifiers")
    @DisplayName("식별자로 선언하지 않은 칸이나 식별자 모양이 아닌 값은 계속 가려 hiddenArgs 가 참이고 승인해도 실행하지 않는다")
    void undeclaredOrMalformedIdentifierStaysHidden(String name, String args) {
        catalogBecomes(withMailIdentifiers(List.of("note_id")));
        UUID actionId = ask(MAIL, args).actionId();

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::hiddenArgs)
                .containsExactly(tuple(actionId, true));
        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.HIDDEN_ARGS);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    static Stream<Arguments> argumentsHiddenDespiteIdentifiers() {
        return Stream.of(
                Arguments.of("선언하지 않은 칸의 긴 id", "{\"other_id\":\"" + LONG_ID + "\"}"),
                Arguments.of("식별자 칸 안의 공백 섞인 글", "{\"note_id\":\"본문 " + HIDDEN_TOKEN + "\"}"),
                Arguments.of("식별자 칸의 base64 + 와 / 글", "{\"note_id\":\"" + HIDDEN_TOKEN + "+/==\"}"),
                Arguments.of("식별자 칸의 알려진 접두사 key", "{\"note_id\":\"ghp_" + LONG_ID + "\"}"),
                Arguments.of(
                        "식별자 칸 밖 본문의 긴 덩어리", "{\"note_id\":\"" + LONG_ID + "\",\"text\":\"" + HIDDEN_TOKEN + "\"}"));
    }

    @Test
    @DisplayName("상시 허락을 줄 수 있는 도구는 인자에 가려지는 글이 있어도 hiddenArgs 가 거짓이고 승인하면 실행한다")
    void grantableToolWithHiddenArgumentsIsStillApproved() {
        String args = "{\"text\":\"회의록입니다\\n" + HIDDEN_TOKEN + "\"}";
        UUID actionId = ask(WRITE, args).actionId();

        ConnectorActionView listed =
                service.listForConversation(me, CONVERSATION).getFirst();
        assertThat(listed.hiddenArgs()).isFalse();
        assertThat(listed.argsJson()).as("화면에 내는 글은 그대로 가린다").doesNotContain(HIDDEN_TOKEN);
        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(PROFILE, DEMO, "mcp__demo__write_note", args);
    }

    @Test
    @DisplayName("도구를 선언하지 않는 판의 선언 없는 도구는 인자에 가려지는 글이 있어도 hiddenArgs 가 거짓이다")
    void undeclaredToolOfLegacySchemaHasNoHiddenArgs() {
        catalogBecomes(legacyManifest());
        ask(WRITE, "{\"text\":\"" + HIDDEN_TOKEN + "\"}");

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::hiddenArgs)
                .containsExactly(false);
    }

    @Test
    @DisplayName("카탈로그를 읽지 못하면 가려지는 글이 있는 줄도 hiddenArgs 가 거짓이고 승인하면 판정하지 못한 줄로 NOT_EXECUTABLE 로 끝난다")
    void unreadableCatalogDoesNotWarnAboutHiddenArguments() {
        UUID hidden = ask(WRITE, "{\"text\":\"" + HIDDEN_TOKEN + "\"}").actionId();
        UUID plain = ask(WRITE, ARGS).actionId();
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::hiddenArgs)
                .containsExactly(tuple(hidden, false), tuple(plain, false));
        ConnectorActionView refused = service.approve(me, hidden, null);

        assertThat(refused.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(refused.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("상시 허락 목록은 지금 선언이 상시 허락을 닫은 도구의 옛 허락 줄을 빼고, 카탈로그를 읽지 못하면 낸다")
    void grantsOmitToolsWhoseDeclarationClosedTheGrant() {
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        assertThat(service.grants(me)).extracting(ConnectorGrantView::toolName).containsExactly(WRITE);

        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", Boolean.FALSE))));
        assertThat(service.grants(me)).as("선언이 상시 허락을 닫았다").isEmpty();
        assertThat(grants.findAll()).as("줄은 지우지 않는다").hasSize(1);

        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());
        assertThat(service.grants(me))
                .as("카탈로그를 읽지 못했다")
                .extracting(ConnectorGrantView::toolName)
                .containsExactly(WRITE);
    }

    @Test
    @DisplayName("선언이 상시 허락을 닫으면 그 도구의 허락 줄을 거두고, 선언이 다시 열려도 되살아나지 않으며 다른 도구의 줄은 그대로다")
    void closedGrantIsRevokedAndStaysRevokedWhenDeclarationReopens() {
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        Instant now = Instant.now();
        grants.save(ConnectorToolGrant.of(owner.id(), DEMO, "other_note", now.plus(Duration.ofDays(1)), now));
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", Boolean.FALSE),
                new ConnectorTool("other_note", "WRITE", "required", null, null))));

        assertThat(service.revokeClosedGrants(now)).isEqualTo(1);

        assertThat(grants.findAll())
                .extracting(ConnectorToolGrant::toolName, grant -> grant.revokedAt() != null)
                .containsExactlyInAnyOrder(tuple(WRITE, true), tuple("other_note", false));
        assertThat(service.revokeClosedGrants(now)).as("거둔 줄은 다시 세지 않는다").isZero();

        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null),
                new ConnectorTool("other_note", "WRITE", "required", null, null))));
        ConnectorPolicyAnswer reopened = ask(WRITE, "{\"text\":\"다시 열린 뒤\"}");

        assertThat(reopened.allowed()).as("다시 열려도 옛 허락은 되살아나지 않는다").isFalse();
        assertThat(reopened.actionId()).isNotNull();
        assertThat(service.grants(me)).extracting(ConnectorGrantView::toolName).containsExactly("other_note");
    }

    @Test
    @DisplayName("도구가 승인을 늘 받는 것으로 바뀌어 허락을 줄 수 없게 되어도 그 도구의 허락 줄을 거둔다")
    void grantIsRevokedWhenToolBecameAlwaysApproved() {
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "always", null, null))));

        assertThat(service.revokeClosedGrants(Instant.now())).isEqualTo(1);

        assertThat(grants.findAll())
                .extracting(grant -> grant.revokedAt() != null)
                .containsExactly(true);
    }

    @Test
    @DisplayName("이미 거두었거나 기간이 지난 줄은 건드리지 않고, 카탈로그를 읽지 못하거나 선언에 없는 도구의 줄은 거두지 않는다")
    void closedGrantSweepLeavesUnknownAndFinishedLinesAlone() {
        // DB 칸이 마이크로초까지라 나노초가 남은 시각은 저장한 뒤 읽으면 달라진다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ConnectorToolGrant revoked = ConnectorToolGrant.of(owner.id(), DEMO, MAIL, now.plus(Duration.ofDays(1)), now);
        Instant earlier = now.minus(Duration.ofHours(1));
        revoked.revoke(earlier);
        grants.save(revoked);
        grants.save(ConnectorToolGrant.of(owner.id(), DEMO, MAIL, now.minusSeconds(1), now.minus(Duration.ofDays(1))));
        grants.save(ConnectorToolGrant.of(owner.id(), DEMO, "removed_note", now.plus(Duration.ofDays(1)), now));
        grants.save(ConnectorToolGrant.of(owner.id(), "other-connector", MAIL, now.plus(Duration.ofDays(1)), now));

        assertThat(service.revokeClosedGrants(now)).isZero();

        assertThat(grants.findAll())
                .filteredOn(grant -> grant.revokedAt() != null)
                .singleElement()
                .extracting(ConnectorToolGrant::revokedAt)
                .as("처음 거둔 시각을 그대로 둔다")
                .isEqualTo(earlier);

        grants.save(ConnectorToolGrant.of(owner.id(), DEMO, MAIL, now.plus(Duration.ofDays(1)), now));
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());
        assertThat(service.revokeClosedGrants(now)).as("카탈로그를 읽지 못했다").isZero();

        doReturn(List.of(DECLARING)).when(connector).readCatalog();
        ConnectorPolicyTestDoubles.expireCatalog();
        assertThat(service.revokeClosedGrants(now)).as("닫은 도구의 유효한 줄 하나").isEqualTo(1);
    }

    @Test
    @DisplayName("승인할 때 그 도구의 선언이 상시 허락을 닫는 것으로 바뀌었으면 허락을 주지 않고 줄은 PENDING 그대로다")
    void grantIsRefusedWhenDeclarationClosedTheGrant() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "required", null, Boolean.FALSE))));

        assertCode(() -> service.approve(me, actionId, GrantPeriod.TODAY), ErrorCode.VALIDATION_FAILED);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("도구를 선언하지 않는 판의 선언 없는 도구는 승인 줄의 grantAllowed 가 참이고 기간을 실어 승인하면 허락이 생긴다")
    void undeclaredToolOfLegacySchemaCanStillBeGranted() {
        catalogBecomes(legacyManifest());
        UUID actionId = ask(WRITE, ARGS).actionId();
        assertThat(onlyAction().toolName()).isEqualTo(WRITE);

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::grantAllowed)
                .containsExactly(tuple(actionId, true));
        ConnectorActionView approved = service.approve(me, actionId, GrantPeriod.HOUR);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(approved.grantAllowed()).isTrue();
        assertThat(service.grants(me))
                .extracting(ConnectorGrantView::connectorId, ConnectorGrantView::toolName)
                .containsExactly(tuple(DEMO, WRITE));
    }

    @Test
    @DisplayName("카탈로그를 읽지 못하면 승인 줄의 grantAllowed 가 거짓이고 기간을 실은 승인은 VALIDATION_FAILED 이며 줄은 PENDING 그대로다")
    void grantIsRefusedWhenCatalogIsUnreadable() {
        catalogBecomes(legacyManifest());
        UUID actionId = ask(WRITE, ARGS).actionId();
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());

        assertThat(service.listForConversation(me, CONVERSATION))
                .extracting(ConnectorActionView::actionId, ConnectorActionView::grantAllowed)
                .containsExactly(tuple(actionId, false));
        assertCode(() -> service.approve(me, actionId, GrantPeriod.HOUR), ErrorCode.VALIDATION_FAILED);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 연결을 붙인 다른 에이전트의 실행이 만든 승인 줄은 승인하면 그 에이전트의 profile 에서 실행한다")
    void approvalExecutesInProfileOfTheAgentThatDecided() {
        Agent other = agents.save(Agent.of(
                "action-other-" + UUID.randomUUID(),
                "다른 비서",
                OTHER_PROFILE,
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now()));
        bind(other, connections.findByUserIdAndConnectorId(owner.id(), DEMO).orElseThrow(), true);
        String otherRoot = "fos-" + UUID.randomUUID();
        executions.save(AgentExecution.builder()
                .userId(owner.id())
                .agentId(other.id())
                .conversationId(CONVERSATION)
                .profileName(OTHER_PROFILE)
                .hermesSessionId(otherRoot)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-10-01T00:00:00Z"))
                .build());
        UUID actionId = policies.decide(
                        OTHER_PROFILE,
                        otherRoot,
                        otherRoot,
                        "call_" + UUID.randomUUID(),
                        "mcp__demo__" + WRITE,
                        WRITE,
                        ARGS)
                .actionId();

        ConnectorActionView approved = service.approve(me, actionId, null);

        assertThat(onlyAction().agentId()).isEqualTo(other.id());
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(OTHER_PROFILE, DEMO, "mcp__demo__write_note", ARGS);
        verify(connector, never()).execute(eq(PROFILE), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인 줄을 만든 에이전트에서 연결을 떼었으면 승인해도 실행하지 않고 NOT_EXECUTABLE 로 끝난다")
    void approvalAfterBindingWasDetachedIsNotExecutable() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        bindings.deleteAll();

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 그 에이전트의 바인딩이 반영 대기면 실행하지 않고 NOT_EXECUTABLE 로 끝난다")
    void approvalOfPendingBindingIsNotExecutable() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        jdbc.update("UPDATE agent_connector_binding SET status = 'PENDING'");

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 연결이 PENDING 이면 실행하지 않고 REJECTED 줄을 오류 없이 돌려준다")
    void approvalOfNotReadyConnectionRejectsTheAction() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        jdbc.update("UPDATE connector_connection SET status = 'PENDING'");

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.REJECTED);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 그 도구가 선언에서 빠졌으면 실행하지 않고 REJECTED 줄을 오류 없이 돌려준다")
    void approvalOfToolRemovedFromDeclarationRejectsTheAction() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        catalogBecomes(manifest(List.of(new ConnectorTool("list_scopes", "READ", "none", null, null))));

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.REJECTED);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 그 도구의 위험도가 DESTRUCTIVE 로 바뀌었으면 실행하지 않고 REJECTED 줄을 오류 없이 돌려준다")
    void approvalOfToolThatBecameDestructiveRejectsTheAction() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "DESTRUCTIVE", "always", null, null))));

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.REJECTED);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 카탈로그를 읽지 못하면 실행하지 않고 REJECTED 줄을 오류 없이 돌려준다")
    void approvalWithUnreadableCatalogRejectsTheAction() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());

        ConnectorActionView closed = service.approve(me, actionId, null);

        assertThat(closed.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(closed.errorCode()).isEqualTo(ConnectorAction.NOT_EXECUTABLE);
        assertThat(onlyAction().status()).isEqualTo(ActionStatus.REJECTED);
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인할 때 그 도구가 늘 승인을 받는 것으로 바뀌었으면 허락을 주지 않고 줄은 PENDING 그대로다")
    void grantIsRefusedWhenToolBecameAlwaysApproved() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        catalogBecomes(manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "always", null, null))));

        assertCode(() -> service.approve(me, actionId, GrantPeriod.TODAY), ErrorCode.VALIDATION_FAILED);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(grants.findAll()).isEmpty();
    }

    @Test
    @DisplayName("연결을 해제하면 그 연결의 PENDING 이 REJECTED 가 되고 허락이 거두어지며 사건이 나간다")
    void disconnectRejectsPendingActionsAndRevokesGrants() {
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        UUID waiting = ask("send_note", ARGS).actionId();
        recorder.seen.clear();

        connectionService.disconnect(me, DEMO);

        assertThat(actions.findAll())
                .extracting(ConnectorAction::status)
                .containsExactlyInAnyOrder(ActionStatus.SUCCEEDED, ActionStatus.REJECTED);
        assertThat(service.grants(me)).isEmpty();
        assertThat(grants.findAll())
                .allSatisfy(grant -> assertThat(grant.revokedAt()).isNotNull());
        assertThat(recorder.seen)
                .extracting(Seen::event)
                .containsExactly(new ConnectorActionChanged(CONVERSATION, waiting));
        // 사건은 해제의 트랜잭션이 커밋한 직후 그 스레드에서 나간다. 그 자리에서도 전했다는 표시가 저장돼야 한다.
        assertThat(actions.findAll())
                .filteredOn(action -> action.publicId().equals(waiting))
                .extracting(ConnectorAction::resultDeliveredAt)
                .doesNotContainNull();
        assertCode(() -> service.approve(me, waiting, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
    }

    @Test
    @DisplayName("값을 다시 등록하면 앞선 값에 한 승인 요청이 REJECTED 가 되고 허락이 거두어진다")
    void registeringAgainRejectsPendingActionsAndRevokesGrants() {
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(JSON.readTree("{\"scopes\":[]}")));
        service.approve(me, ask(WRITE, ARGS).actionId(), GrantPeriod.DAYS_30);
        UUID waiting = ask("send_note", ARGS).actionId();

        connectionService.register(me, DEMO, Map.of());

        assertThat(actions.findAll())
                .extracting(ConnectorAction::status)
                .containsExactlyInAnyOrder(ActionStatus.SUCCEEDED, ActionStatus.REJECTED);
        assertThat(service.grants(me)).isEmpty();
        assertThat(actions.findAll())
                .filteredOn(action -> action.publicId().equals(waiting))
                .extracting(ConnectorAction::errorCode)
                .containsExactly(ConnectorAction.CONNECTION_CHANGED);
        assertCode(() -> service.approve(me, waiting, null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인한 호출이 실행되는 동안 값을 다시 등록하면 CONNECTOR_ACTION_EXECUTING 이고 env 를 바꾸지 않는다")
    void registeringWhileAnApprovedCallExecutesIsRefused() throws Exception {
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(JSON.readTree("{\"scopes\":[]}")));
        UUID actionId = ask(WRITE, ARGS).actionId();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    executing.countDown();
                    await(release);
                    return CallResult.success(JSON.readTree("{\"saved\":true}"));
                });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ConnectorActionView> approving = pool.submit(() -> service.approve(me, actionId, null));
            await(executing);

            // 실행은 앞선 계정 값으로 돌고 있다. 그 사이 값을 바꾸면 그 승인이 새 계정으로 실행된다.
            assertCode(() -> connectionService.register(me, DEMO, Map.of()), ErrorCode.CONNECTOR_ACTION_EXECUTING);
            assertCode(() -> connectionService.disconnect(me, DEMO), ErrorCode.CONNECTOR_ACTION_EXECUTING);

            verify(connector, never()).putEnv(anyString(), anyString(), anyString());
            verify(connector, never()).deleteEnv(anyString(), anyString());
            verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean(), anyString());
            assertThat(connections.findAll())
                    .extracting(ConnectorConnection::status)
                    .containsExactly(ConnectionStatus.READY);
            release.countDown();
            assertThat(approving.get(10, TimeUnit.SECONDS).status()).isEqualTo(ActionStatus.SUCCEEDED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("실행이 끝난 뒤에는 값을 다시 등록할 수 있다")
    void registeringAfterTheApprovedCallFinishedIsAccepted() {
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(JSON.readTree("{\"scopes\":[]}")));
        service.approve(me, ask(WRITE, ARGS).actionId(), null);

        connectionService.register(me, DEMO, Map.of());

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("대화가 있는 실행의 승인 요청은 승인 카드와 결과 전달을 약속하고, 대화 없는 실행은 약속하지 않는다")
    void approvalMessagePromisesOnlyWhatHappens() {
        assertThat(ask(WRITE, ARGS).message()).contains("승인 카드", "결과가 이 대화로 온다");

        jdbc.update("UPDATE agent_execution SET conversation_id = NULL");

        assertThat(ask("send_note", ARGS).message())
                .contains("승인받을 화면이 없으므로 실행되지 않는다")
                .doesNotContain("결과가 이 대화로 온다");
    }

    @Test
    @DisplayName("오래 EXECUTING 으로 남은 줄만 UNKNOWN 으로 바꿔 그 연결의 다시 등록이 다시 열린다")
    void staleExecutionBecomesUnknown() {
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(JSON.readTree("{\"scopes\":[]}")));
        ask(WRITE, ARGS);
        Instant decided = Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("UPDATE connector_action SET status = 'EXECUTING', decided_at = ?", Timestamp.from(decided));

        assertThat(service.markStale(decided, decided.plusSeconds(600)))
                .as("경계 시각의 줄")
                .isZero();
        assertCode(() -> connectionService.register(me, DEMO, Map.of()), ErrorCode.CONNECTOR_ACTION_EXECUTING);

        assertThat(service.markStale(decided.plusSeconds(1), decided.plusSeconds(600)))
                .isEqualTo(1);

        assertThat(onlyAction().status()).isEqualTo(ActionStatus.UNKNOWN);
        connectionService.register(me, DEMO, Map.of());
    }

    @Test
    @DisplayName("승인 줄 응답의 인자는 비밀처럼 보이는 값만 가리고 식별자는 그대로 보인다")
    void viewRedactsSecretsButKeepsIdentifiers() {
        String id = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
        ask(WRITE, "{\"page_id\":\"" + id + "\",\"api_token\":\"abc\",\"text\":\"안녕\"}");

        String shown = service.listForConversation(me, CONVERSATION).getFirst().argsJson();

        assertThat(shown).contains(id, "안녕", "[가림]").doesNotContain("abc");
        assertThat(onlyAction().argsJson()).as("실행에 쓰는 저장한 원문").contains("abc");
    }

    @Test
    @DisplayName("TODAY 허락은 서버 시간대와 상관없이 Asia/Seoul 의 다음 날 0시에 끝난다")
    void todayGrantEndsAtSeoulMidnightRegardlessOfServerZone() {
        UUID actionId = ask(WRITE, ARGS).actionId();

        service.approve(me, actionId, GrantPeriod.TODAY);

        // 다음 날 0시는 UTC 로는 15시다. 서버 시간대(UTC)의 그날 끝이면 0시가 된다.
        Instant expiresAt = grants.findAll().getFirst().expiresAt();
        assertThat(expiresAt.atZone(ZoneId.of("Asia/Seoul")).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(Duration.between(Instant.now(Clock.systemUTC()), expiresAt))
                .isBetween(Duration.ZERO, Duration.ofDays(1));
    }

    @Test
    @DisplayName("대화의 승인 줄은 PENDING 전부와 끝난 것 최근 20개를 만든 순으로 주고 판정만 한 줄은 뺀다")
    void listReturnsAllPendingAndLatestTwentyFinishedInCreationOrder() {
        List<UUID> finished = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            UUID actionId = ask(WRITE, "{\"index\":" + index + "}").actionId();
            service.reject(me, actionId);
            finished.add(actionId);
        }
        UUID firstPending = ask(WRITE, "{\"text\":\"기다림 1\"}").actionId();
        UUID secondPending = ask("send_note", "{\"text\":\"기다림 2\"}").actionId();
        ask("list_scopes", "{}");

        List<ConnectorActionView> listed = service.listForConversation(me, CONVERSATION);

        List<UUID> expected = new ArrayList<>(finished.subList(1, 21));
        expected.add(firstPending);
        expected.add(secondPending);
        assertThat(listed).extracting(ConnectorActionView::actionId).containsExactlyElementsOf(expected);
        // 이름은 카탈로그의 선언에서 읽고, 선언에 이름이 없으면 고정 문구다. 도구의 원래 이름은 싣지 않는다.
        assertThat(listed.get(20).title()).isEqualTo("메모 쓰기");
        assertThat(listed.get(20).argsJson()).isEqualTo("{\"text\":\"기다림 1\"}");
        assertThat(listed.get(21).title()).isEqualTo(ConnectorActionView.UNNAMED_TITLE);
        assertThat(listed.get(21).grantAllowed()).isFalse();
        assertThat(service.listForConversation(me, CONVERSATION + 1)).isEmpty();
    }

    @Test
    @DisplayName("카탈로그를 읽지 못해도 승인 줄 목록은 나오고 이름은 고정 문구다")
    void listSurvivesUnreadableCatalog() {
        UUID actionId = ask(WRITE, ARGS).actionId();
        ConnectorPolicyTestDoubles.expireCatalog();
        when(connector.readCatalog()).thenThrow(new IllegalStateException());

        List<ConnectorActionView> listed = service.listForConversation(me, CONVERSATION);

        assertThat(listed)
                .extracting(ConnectorActionView::actionId, ConnectorActionView::title)
                .containsExactly(tuple(actionId, ConnectorActionView.UNNAMED_TITLE));
    }

    /** 주인의 연결을 만들어 에이전트에 붙인다. {@code ready} 가 거짓이면 연결과 바인딩을 {@code PENDING} 으로 둔다. */
    private void connect(boolean ready) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, now);
        if (ready) {
            connection.ready(now);
        }
        bind(agent, connections.save(connection), ready);
    }

    private ConnectorBinding bind(Agent target, ConnectorConnection connection, boolean ready) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ConnectorBinding binding = ConnectorBinding.pending(target, connection, "demo", now);
        if (ready) {
            binding.ready(now);
        }
        return bindings.save(binding);
    }

    /** 루트 session 에서 부른 새 호출 하나의 판정을 묻는다. */
    private ConnectorPolicyAnswer ask(String tool, String args) {
        return policies.decide(PROFILE, root, root, "call_" + UUID.randomUUID(), "mcp__demo__" + tool, tool, args);
    }

    /**
     * 쓰기 도구를 허용한 살펴보기 turn 을 루트로 둔 커넥터 에이전트의 위임 실행을 만들고, 그 실행에서 부른 새 호출 하나를 판정한다.
     */
    private ConnectorPolicyAnswer askInWritesAllowedCheck(String tool, String args) {
        Instant started = Instant.parse("2026-10-01T00:00:00Z");
        AgentExecution checkTurn = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .conversationId(CONVERSATION)
                .profileName(PROFILE)
                .hermesSessionId("fos-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(started)
                .build());
        ProactiveCheck check =
                ProactiveCheck.started(owner.id(), agent.id(), CONVERSATION, CheckTrigger.MANUAL, true, started);
        check.attachRoot(checkTurn.id(), checkTurn.hermesSessionId());
        createdChecks.add(checks.save(check).id());
        String childRoot = "fos-" + UUID.randomUUID();
        executions.save(AgentExecution.builder()
                .userId(owner.id())
                .agentId(agent.id())
                .conversationId(CONVERSATION)
                .parentExecutionId(checkTurn.id())
                .rootExecutionId(checkTurn.id())
                .delegationKey("check-" + UUID.randomUUID())
                .profileName(PROFILE)
                .hermesSessionId(childRoot)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(started)
                .build());
        return policies.decide(
                PROFILE, childRoot, childRoot, "call_" + UUID.randomUUID(), "mcp__demo__" + tool, tool, args);
    }

    /** 카탈로그를 바꾸고, 다음 판정이 다시 읽게 보관 시간보다 멀리 옮긴다. */
    private void catalogBecomes(ConnectorManifest manifest) {
        when(connector.readCatalog()).thenReturn(List.of(manifest));
        ConnectorPolicyTestDoubles.expireCatalog();
    }

    private ConnectorAction onlyAction() {
        List<ConnectorAction> rows = actions.findAll();
        assertThat(rows).as("connector_action 의 줄").hasSize(1);
        return rows.get(0);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 판단 피드백을 {@code (subjectKey, eventType, actor, subjectVersion)} 로 남긴 순서대로 읽는다. */
    private List<Tuple> feedbackRows() {
        return jdbc
                .queryForList(
                        "SELECT subject_key, event_type, actor, subject_version FROM decision_feedback_event ORDER BY id")
                .stream()
                .map(row -> tuple(
                        row.get("SUBJECT_KEY"), row.get("EVENT_TYPE"), row.get("ACTOR"), row.get("SUBJECT_VERSION")))
                .toList();
    }

    private static CurrentUser currentUser(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    /** 도구를 선언하지 않는 판의 커넥터다. 대시보드가 부르는 읽기 도구만 담는다. */
    private static ConnectorManifest legacyManifest() {
        return new ConnectorManifest(
                DEMO,
                "검사용 메모",
                "",
                List.of(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                1,
                List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)));
    }

    /** {@link #DECLARING} 에서 상시 허락을 닫은 도구가 식별자 인자를 선언한 커넥터다(ADR-089). */
    private static ConnectorManifest withMailIdentifiers(List<String> identifiers) {
        return manifest(List.of(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null),
                new ConnectorTool("send_note", "WRITE", "always", null, null),
                new ConnectorTool(MAIL, "WRITE", "required", "메모 보내기", Boolean.FALSE, identifiers)));
    }

    private static ConnectorManifest manifest(List<ConnectorTool> tools) {
        return new ConnectorManifest(DEMO, "검사용 메모", "", List.of(), "list_scopes", "demo", List.of(), false, 2, tools);
    }
}
