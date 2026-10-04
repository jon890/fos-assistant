package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.RestartReconciler;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.proactive.application.ProactiveCheckRecovery;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버가 도중에 내려가 {@code RUNNING} 으로 남은 먼저 살펴보기를 기동할 때 닫는지 본다.
 *
 * <p>기동은 이미 지났으므로 같은 빈 셋으로 새 복구를 만들어 시작한다. 시각은 고정한다. Hermes 는 대역이고 받은 중지를 적는다. 모든
 * 데이터는 합성이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ProactiveCheckRecoveryTest.StubRuntime.class)
class ProactiveCheckRecoveryTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    private static final Instant STARTED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-01T00:10:00Z");

    @Autowired
    ProactiveCheckRecovery recovery;

    @Autowired
    RestartReconciler reconciler;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    TransactionTemplate transactions;

    private final List<Long> createdChecks = new ArrayList<>();
    private final List<Long> createdExecutions = new ArrayList<>();

    private AppUser user;
    private Agent agent;
    private Agent connector;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        stub().reset();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = users.save(AppUser.of("recovery-" + suffix + "@example.com", "복구", 1L, UserRole.MEMBER, STARTED));
        agent = agents.save(agent("recovery-" + suffix, false));
        connector = agents.save(agent("recovery-connector-" + suffix, true));
        conversation =
                conversations.save(Conversation.startedForCheck(user.id(), "먼저 살펴보기 · 커리어", agent.id(), STARTED));
    }

    @AfterEach
    void tearDown() {
        checks.deleteAllById(createdChecks);
        executions.deleteAllById(createdExecutions);
        transactions.executeWithoutResult(status -> {
            messages.deleteAll(messages.findByConversationIdOrderByIdAsc(conversation.id()));
            conversations.deleteById(conversation.id());
        });
        agents.deleteById(connector.id());
        agents.deleteById(agent.id());
        users.deleteById(user.id());
    }

    @Test
    @DisplayName("RUNNING 으로 남은 살펴보기는 FAILED 와 INTERRUPTED 로 닫히고 그 트리의 도는 자식과 끝난 자식에 전달 표시가 붙는다")
    void closesRunningCheckAsInterruptedAndMarksTreeDelivered() {
        AgentExecution root = rootTurn();
        AgentExecution runningChild = child(root, ExecutionStatus.RUNNING);
        AgentExecution finishedChild = child(root, ExecutionStatus.SUCCEEDED);
        ProactiveCheck running = check(root);

        newRecovery().start();

        ProactiveCheck closed = checks.findById(running.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(CheckStatus.FAILED);
        assertThat(closed.errorCode()).isEqualTo("INTERRUPTED");
        assertThat(closed.finishedAt()).isEqualTo(NOW);
        assertThat(executions.findById(runningChild.id()).orElseThrow().resultDeliveredAt())
                .as("도는 자식의 전달 표시")
                .isEqualTo(NOW);
        assertThat(executions.findById(finishedChild.id()).orElseThrow().resultDeliveredAt())
                .as("끝난 자식의 전달 표시")
                .isEqualTo(NOW);
        assertThat(executions.findUndeliveredResults(conversation.id()))
                .as("점검 대화에 전할 위임 결과")
                .isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .as("대화에 남은 알림 줄")
                .isEmpty();
    }

    @Test
    @DisplayName("닫은 살펴보기 트리에서 run 번호가 있는 도는 위임 자식에만 Hermes 중지가 가고 다시 붙을 루트 turn 은 멈추지 않는다")
    void stopsRunningDelegatedChildrenButNotRootTurn() {
        AgentExecution root = rootTurn();
        AgentExecution runningChild = child(root, ExecutionStatus.RUNNING);
        AgentExecution finishedChild = child(root, ExecutionStatus.SUCCEEDED);
        check(root);

        newRecovery().start();

        assertThat(stub().stopped())
                .as("Hermes 에 보낸 중지")
                .containsExactly(runningChild.hermesRunId())
                .doesNotContain(root.hermesRunId(), finishedChild.hermesRunId());
        assertThat(executions.findById(root.id()).orElseThrow().status())
                .as("루트 turn 은 기동 정리가 다시 붙는다")
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("루트 실행이 생기기 전에 남은 살펴보기도 닫고 이미 끝난 살펴보기는 그대로 둔다")
    void closesCheckWithoutRootAndLeavesEndedCheck() {
        ProactiveCheck withoutRoot = check(null);
        ProactiveCheck ended =
                ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, STARTED);
        ended.succeed(CheckOutcome.NOTHING_NEW, 0, 0, 3, 0, STARTED.plusSeconds(60));
        ended = checks.save(ended);
        createdChecks.add(ended.id());

        newRecovery().start();

        ProactiveCheck closed = checks.findById(withoutRoot.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(CheckStatus.FAILED);
        assertThat(closed.errorCode()).isEqualTo("INTERRUPTED");
        ProactiveCheck untouched = checks.findById(ended.id()).orElseThrow();
        assertThat(untouched.status()).isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(untouched.errorCode()).isNull();
        assertThat(untouched.finishedAt()).isEqualTo(STARTED.plusSeconds(60));
        assertThat(stub().stopped()).as("루트가 없으면 멈출 자식도 없다").isEmpty();
    }

    @Test
    @DisplayName("복구는 기동 정리보다 앞선 lifecycle 단계에서 시작하고 이미 시작돼 있다")
    void startsBeforeRestartReconciler() {
        assertThat(recovery.getPhase())
                .as("복구의 단계 %d 는 기동 정리의 단계 %d 보다 앞선다", recovery.getPhase(), reconciler.getPhase())
                .isLessThan(reconciler.getPhase());
        assertThat(recovery.isAutoStartup()).isTrue();
        assertThat(recovery.isRunning()).isTrue();
    }

    private ProactiveCheckRecovery newRecovery() {
        return new ProactiveCheckRecovery(checks, deliveryWriter, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 점검 대화의 {@code RUNNING} 살펴보기 줄이다. {@code root} 가 null 이면 루트 실행이 생기기 전이다. */
    private ProactiveCheck check(AgentExecution root) {
        ProactiveCheck check =
                ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, STARTED);
        if (root != null) {
            check.attachRoot(root.id(), root.hermesSessionId());
        }
        ProactiveCheck saved = checks.save(check);
        createdChecks.add(saved.id());
        return saved;
    }

    /** 점검 대화에서 돌던 살펴보기 turn 의 실행 줄이다. */
    private AgentExecution rootTurn() {
        return keep(AgentExecution.builder()
                .userId(user.id())
                .agentId(agent.id())
                .conversationId(conversation.id())
                .profileName(agent.hermesProfile())
                .hermesSessionId("fos-" + UUID.randomUUID())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(STARTED)
                .build());
    }

    /** 그 살펴보기 turn 이 커넥터 에이전트에 맡긴 위임 자식이다. */
    private AgentExecution child(AgentExecution root, ExecutionStatus status) {
        AgentExecution child = AgentExecution.builder()
                .userId(user.id())
                .agentId(connector.id())
                .conversationId(conversation.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.id())
                .delegationKey(UUID.randomUUID().toString())
                .profileName(connector.hermesProfile())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(STARTED)
                .build();
        if (status != ExecutionStatus.RUNNING) {
            child.recordOutput("커리어 문서를 읽은 결과");
        }
        return keep(child);
    }

    private AgentExecution keep(AgentExecution execution) {
        AgentExecution saved = executions.save(execution);
        createdExecutions.add(saved.id());
        return saved;
    }

    private Agent agent(String code, boolean connectorManaged) {
        Agent created = Agent.of(
                code,
                connectorManaged ? "채용 연결" : "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                STARTED);
        if (connectorManaged) {
            created.markConnectorManaged();
        }
        return created;
    }
}
