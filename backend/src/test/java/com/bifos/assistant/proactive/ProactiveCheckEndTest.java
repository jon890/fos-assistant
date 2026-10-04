package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.NextTurnDispatcher;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.proactive.application.ProactiveCheckEnded;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기가 끝날 때 그 트리의 위임 결과를 전했다고 적고, 도는 위임 자식을 멈추고, 위임 수를 적는지 본다.
 *
 * <p>위임 결과 깨우기를 켜고 띄운다. 끝난 위임 결과가 남아 있으면 점검 대화에 자동 turn 이 열리는 설정이다. 위임 자식 줄은
 * 살펴보기 turn 이 완료를 기다리는 자리에서 직접 만든다. 도는 자식은 이 서버가 돌리지 않는 실행이다. 모든 데이터는 합성이다.
 */
@SpringBootTest(
        properties = {
            "hermes.run-timeout=30s",
            "assistant.proactive-check.max-duration=20s",
            "assistant.delegation-wake.enabled=true"
        })
@ActiveProfiles("test")
@Import(ProactiveCheckEndTest.StubRuntime.class)
class ProactiveCheckEndTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String START_NOTICE = "먼저 살펴보기를 시작했어요";

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    ProactiveCheckService service;

    @Autowired
    NextTurnDispatcher dispatcher;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    /** 중지가 닿기 전에 끝난 자식을 만드는 검사만 바꾼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @MockitoSpyBean
    AgentDelegationService delegations;

    /** 실제 Hermes 를 부르지 않도록 켜진 toolset 을 대역으로 둔다. */
    @MockitoBean
    HermesToolsetClient toolsets;

    /** 켜진 스킬 목록을 대역으로 둔다. */
    @MockitoBean
    HermesSkillClient skillClient;

    /** 실제 스트림 주소로 연결하지 않게 대역으로 둔다. 사건은 흘리지 않는다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    private CurrentUser owner;
    private Agent agent;
    private Agent connector;
    private Conversation conversation;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user =
                users.save(AppUser.of("end-" + suffix + "@example.com", "끝", 1L, UserRole.MEMBER, Instant.now()));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        agent = agents.save(agent("end-" + suffix, "커리어"));
        Agent connected = agent("end-connector-" + suffix, "채용 연결");
        connected.markConnectorManaged();
        connector = agents.save(connected);
        conversation = conversations.save(
                Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 커리어", agent.id(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        awaitIdle();
        stub().reset();
        checks.deleteAll(checks.findAll().stream()
                .filter(check -> check.userId().equals(owner.id()))
                .toList());
        List<AgentExecution> ownExecutions = executions.findAll().stream()
                .filter(execution -> execution.userId().equals(owner.id()))
                .toList();
        executionEvents.deleteAll(executionEvents.findAll().stream()
                .filter(event -> ownExecutions.stream()
                        .anyMatch(execution -> execution.id().equals(event.executionId())))
                .toList());
        executions.deleteAll(ownExecutions);
        transactions.executeWithoutResult(status -> {
            messages.deleteAll(messages.findByConversationIdOrderByIdAsc(conversation.id()));
            conversations.deleteById(conversation.id());
        });
        agents.deleteById(connector.id());
        agents.deleteById(agent.id());
        users.deleteById(owner.id());
    }

    @Test
    @DisplayName("끝난 자식과 도는 자식을 둔 살펴보기가 끝나면 둘 다 전했다고 적히고 위임 수가 2 이며 자동 turn 이 열리지 않는다")
    void marksTreeDeliveredCountsDelegationsAndOpensNoAutoTurn() {
        String runningRunId = "run-child-" + UUID.randomUUID();
        stub().beforeAwait(() -> leaveChildren(runningRunId));
        stub().willAnswer(command ->
                result("<fos-check-result>\n{\"version\":1,\"outcome\":\"NOTHING_NEW\"}\n</fos-check-result>"));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(check.delegations()).isEqualTo(2);
        assertThat(executions.findByRootExecutionId(check.rootExecutionId()))
                .as("두 위임 자식의 결과 전달 시각")
                .hasSize(2)
                .allSatisfy(child -> assertThat(child.resultDeliveredAt())
                        .as("실행 %d", child.id())
                        .isNotNull());
        assertThat(stub().stopped()).as("도는 자식에 간 중지").contains(runningRunId);
        assertNoAutoTurn("살펴봤지만 새로 알릴 것이 없어요", 1);
    }

    @Test
    @DisplayName("이 서버가 돌리는 위임 자식이 살펴보기가 끝난 뒤 SUCCEEDED 로 끝나도 전달 표시가 남고 자동 turn 이 열리지 않는다")
    void opensNoAutoTurnWhenInProcessChildSucceedsAfterCheckEnds() {
        String task = "합성 채용 공고 목록을 읽어 줘";
        AtomicBoolean checkTurnAwaiting = new AtomicBoolean();
        AtomicReference<Long> childId = new AtomicReference<>();
        stub().beforeAwait(() -> {
            if (checkTurnAwaiting.compareAndSet(false, true)) {
                // 살펴보기 turn 이 커넥터 에이전트에 맡긴다. agent_delegate 가 부르는 자리다.
                AgentExecution origin = executions
                        .findById(turns.markOf(conversation.id()).executionId())
                        .orElseThrow();
                String session = origin.hermesSessionId();
                DelegationResult started = delegations.delegate(
                        owner,
                        origin,
                        DelegationKey.of(agent.hermesProfile(), session, session, "call_" + UUID.randomUUID()),
                        connector.code(),
                        task);
                childId.set(started.executionId());
                return;
            }
            // 맡긴 자식은 살펴보기가 끝나 잠금이 풀릴 때까지 Hermes 에서 돈다.
            waitUntil(() -> !turns.markOf(conversation.id()).running(), "살펴보기 turn 이 닫힌다");
        });
        stub().willAnswer(command -> task.equals(command.input())
                ? result("커넥터의 답")
                : result("<fos-check-result>\n{\"version\":1,\"outcome\":\"NOTHING_NEW\"}\n</fos-check-result>"));
        // 중지가 닿기 전에 Hermes 에서 끝난 자식이다. 끝날 때의 정리는 전달 표시만 하고 중지는 보내지 않는다.
        doAnswer(invocation -> deliveryWriter.markTreeDelivered(invocation.getArgument(0), Instant.now()))
                .when(delegations)
                .stopRunningChildrenOf(any());

        runCheck();
        waitUntil(
                () -> childId.get() != null
                        && executions.findById(childId.get()).orElseThrow().status() != ExecutionStatus.RUNNING,
                "맡긴 자식이 끝난다");

        AgentExecution child = executions.findById(childId.get()).orElseThrow();
        assertThat(child.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(child.resultDeliveredAt())
                .as("살펴보기가 끝난 뒤 SUCCEEDED 로 저장된 자식의 전달 표시")
                .isNotNull();
        assertThat(onlyCheck().delegations()).isEqualTo(1);
        assertNoAutoTurn("살펴봤지만 새로 알릴 것이 없어요", 2);
    }

    @Test
    @DisplayName("ProactiveCheckEnded 를 받으면 그 루트의 도는 위임 자식에 중지가 가고 결과를 전했다고 적힌다")
    void stopsRunningChildWhenProactiveCheckEndedIsPublished() {
        AgentExecution root = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .conversationId(conversation.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        String runningRunId = "run-child-" + UUID.randomUUID();
        AgentExecution running = child(root.id(), ExecutionStatus.RUNNING, runningRunId, null);

        publisher.publishEvent(new ProactiveCheckEnded(root.id()));

        assertThat(stub().stopped()).containsExactly(runningRunId);
        assertThat(executions.findById(running.id()).orElseThrow().resultDeliveredAt())
                .isNotNull();
    }

    @Test
    @DisplayName("살펴보기가 예외로 끝나도 위임 결과를 전했다고 적고 도는 자식을 멈추고 위임 수를 적는다")
    void cleansUpTreeWhenCheckFails() {
        String runningRunId = "run-child-" + UUID.randomUUID();
        stub().beforeAwait(() -> {
            leaveChildren(runningRunId);
            stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "runtime went away"));
        });
        stub().willAnswer(command -> result("답"));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.FAILED);
        assertThat(check.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        assertThat(check.delegations()).isEqualTo(2);
        assertThat(executions.findByRootExecutionId(check.rootExecutionId()))
                .hasSize(2)
                .allSatisfy(child -> assertThat(child.resultDeliveredAt())
                        .as("실행 %d", child.id())
                        .isNotNull());
        assertThat(stub().stopped()).as("도는 자식에 간 중지").contains(runningRunId);
        assertNoAutoTurn("살펴보기를 끝내지 못했어요. 잠시 뒤 다시 눌러 주세요", 1);
    }

    /**
     * 살펴보기 turn 아래에 끝난 위임 자식 하나와 도는 위임 자식 하나를 둔다. 완료를 기다리는 자리에서 부른다. 그때 그 대화의 turn 은
     * 살펴보기 turn 의 실행 번호로 잡혀 있다.
     */
    private void leaveChildren(String runningRunId) {
        Long rootId = turns.markOf(conversation.id()).executionId();
        child(rootId, ExecutionStatus.SUCCEEDED, "run-done-" + UUID.randomUUID(), "끝난 위임의 답");
        child(rootId, ExecutionStatus.RUNNING, runningRunId, null);
    }

    private AgentExecution child(Long rootId, ExecutionStatus status, String runId, String output) {
        AgentExecution execution = AgentExecution.builder()
                .userId(owner.id())
                .conversationId(conversation.id())
                .agentId(connector.id())
                .parentExecutionId(rootId)
                .rootExecutionId(rootId)
                .delegationKey(UUID.randomUUID().toString())
                .profileName(connector.hermesProfile())
                .hermesRunId(runId)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(Instant.now())
                .build();
        execution.recordOutput(output);
        return executions.save(execution);
    }

    /**
     * 다음 turn 을 정하는 자리를 한 번 더 불러도 점검 대화에 자동 turn 이 열리지 않는다. 대화에는 시작 줄과 끝 줄만 있고 Hermes 에
     * 보낸 실행은 살펴보기와 그 살펴보기가 맡긴 것뿐이다.
     *
     * @param sentRuns 살펴보기 turn 과 이 서버가 돌린 위임 자식이 Hermes 에 보낸 실행 수
     */
    private void assertNoAutoTurn(String lastNotice, int sentRuns) {
        dispatcher.tryNext(conversation.id());
        awaitIdle();
        assertThat(executions.findUndeliveredResults(conversation.id()))
                .as("전하지 않은 위임 결과")
                .isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly(START_NOTICE, lastNotice);
        assertThat(stub().received()).as("Hermes 에 보낸 실행").hasSize(sentRuns);
        assertThat(conversations.findById(conversation.id()).orElseThrow().autoTurnCount())
                .isZero();
    }

    private void runCheck() {
        service.start(owner, agent.code(), CheckTrigger.MANUAL);
        awaitIdle();
    }

    private ProactiveCheck onlyCheck() {
        List<ProactiveCheck> found = checks.findAll().stream()
                .filter(check -> check.conversationId().equals(conversation.id()))
                .toList();
        assertThat(found).as("점검 대화의 살펴보기 줄").hasSize(1);
        return found.getFirst();
    }

    private Agent agent(String code, String name) {
        return Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner == null ? null : owner.id(),
                Instant.now());
    }

    private static HermesRunResult result(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }

    /** 점검 대화에 도는 turn 이 없어질 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private void awaitIdle() {
        waitUntil(() -> !turns.markOf(conversation.id()).running(), "대화 " + conversation.id() + " 의 turn 이 끝난다");
    }

    /** 조건이 참이 될 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private static void waitUntil(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("%s 안에 조건을 만족하지 못했다: %s", WAIT_LIMIT, description);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("기다리는 중에 끊겼다");
            }
        }
    }
}
