package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.SmallExecutionLimit;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * 위임 자식과 흐름 단계가 사용자 실행 한도에 닿으면 기다리지 않고 거절되는지 본다(ADR-069).
 *
 * <p>한도를 2 로 둔다. 부모 turn 이 자리 하나를 쥔 채 자식 자리를 기다리는 교착이 없어야 한다. 붙잡는 것은 그 실행의 글을
 * 담은 제출만이다.
 */
@BackendIntegrationTest
@SmallExecutionLimit
class UserExecutionLimitDelegationTest {

    /** {@code ChatServiceTest.StubRuntime} 은 chat 패키지 안에서만 보여 이 패키지에서 import 하지 못하므로 따로 둔다. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 위임 거절이 이만큼 안에 와야 한다. 제출 대기 시간(기본 30초)보다 훨씬 짧다. */
    private static final Duration PROMPT_REJECTION = Duration.ofSeconds(2);

    private static final String PARENT_MARK = "부모 turn";
    private static final String CHILD_MARK = "맡긴 첫 일";

    /** 흐름의 Chief 에게 준 지시에만 들어 있는 말이다. 대역이 이것으로 단계를 가려낸다. */
    private static final String CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

    private static final String SPLIT_JSON = "{\"research\":\"전기차 보조금\",\"build\":\"비교 표\"}";

    @Autowired
    ChatService chat;

    @Autowired
    AgentDelegationService delegations;

    @Autowired
    UserExecutionLimiter limiter;

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
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private final CountDownLatch releaseParent = new CountDownLatch(1);
    private final CountDownLatch releaseChild = new CountDownLatch(1);
    private ExecutorService pool;
    private CurrentUser dad;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        AppUser user = users.save(AppUser.of("limit-dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        agents.save(agent("limit-chief", null));
        agents.save(agent("limit-worker", null));
        agents.save(agent("limit-flow", ResearchAndBuildFlow.NAME));
    }

    @AfterEach
    void tearDown() {
        releaseParent.countDown();
        releaseChild.countDown();
        pool.close();
    }

    @Test
    @DisplayName("부모 turn 과 위임 자식 하나가 자리를 채우면 둘째 위임은 곧바로 BUSY 이고 부모 turn 은 끝까지 돈다")
    void secondDelegationIsBusyImmediatelyAndParentTurnFinishes() throws Exception {
        CountDownLatch parentSubmitted = new CountDownLatch(1);
        CountDownLatch childSubmitted = new CountDownLatch(1);
        stub().willAnswer(command -> {
            if (command.input().contains(PARENT_MARK)) {
                parentSubmitted.countDown();
                await(releaseParent);
                return completed("run-parent", "부모 답");
            }
            if (command.input().contains(CHILD_MARK)) {
                childSubmitted.countDown();
                await(releaseChild);
                return completed("run-child", "자식 답");
            }
            return completed("run-" + UUID.randomUUID(), "다른 답");
        });
        Future<ChatTurn> parent = pool.submit(() -> chat.send(dad, null, PARENT_MARK, "limit-chief"));
        await(parentSubmitted);
        AgentExecution origin = latestExecution();
        Future<DelegationResult> first = pool.submit(() -> delegate(origin, CHILD_MARK));
        await(childSubmitted);
        assertThat(limiter.used(dad.id())).as("부모 turn 과 자식 하나가 쥔 자리").isEqualTo(2);
        int rowsBefore = executionsOfDad().size();

        long startedAt = System.nanoTime();
        DelegationResult second = delegate(origin, "맡긴 둘째 일");
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(second.accepted()).as("둘째 위임: %s", second).isFalse();
        assertThat(second.failure()).isEqualTo(DelegationResult.Failure.BUSY);
        assertThat(waited).as("둘째 위임이 거절되기까지 걸린 시간").isLessThan(PROMPT_REJECTION);
        assertThat(executionsOfDad()).as("둘째 위임 뒤 dad 의 실행 줄").hasSize(rowsBefore);

        releaseChild.countDown();
        DelegationResult firstResult = first.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(firstResult.accepted()).as("첫 위임: %s", firstResult).isTrue();
        releaseParent.countDown();
        ChatTurn finished = parent.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(finished.assistantText()).as("부모 turn 의 답").isEqualTo("부모 답");
        assertThat(stub().received())
                .as("Hermes 에 간 제출의 글")
                .extracting(command ->
                        command.input().contains(PARENT_MARK) || command.input().contains(CHILD_MARK))
                .containsOnly(true)
                .hasSize(2);
    }

    @Test
    @DisplayName("흐름 단계 둘 가운데 자리가 하나뿐이면 한쪽만 돌고 흐름은 그 단계를 기다린 뒤 USER_BUSY 로 끝난다")
    void flowEndsWithUserBusyAfterWaitingForAdmittedStepWhenOnlyOneSlotRemains() {
        CountDownLatch stepRejected = new CountDownLatch(1);
        doAnswer(invocation -> {
                    try {
                        return invocation.callRealMethod();
                    } catch (ApiException ex) {
                        if (ex.code() == ErrorCode.USER_BUSY) {
                            stepRejected.countDown();
                        }
                        throw ex;
                    }
                })
                .when(limiter)
                .admit(any(), any(), any());
        stub().willAnswer(command -> {
            String input = command.input();
            if (input.contains(CHIEF_MARK)) {
                return completed("run-chief", SPLIT_JSON);
            }
            if (input.contains("조사해") || input.contains("만든다")) {
                // 들어온 단계는 다른 단계가 거절될 때까지 끝나지 않는다. 먼저 끝나면 그 자리가 다른 단계에 돌아간다.
                await(stepRejected);
                return completed("run-step", "단계 답");
            }
            return completed("run-synthesizer", "합친 답");
        });

        assertThatThrownBy(() -> chat.send(dad, null, "전기차를 사는 게 나을까?", "limit-flow"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.USER_BUSY);

        List<AgentExecution> rows = executionsOfDad();
        assertThat(rows).as("Chief 와 들어온 단계 하나").hasSize(2);
        assertThat(rows)
                .filteredOn(row -> row.parentExecutionId() == null)
                .singleElement()
                .satisfies(root -> {
                    assertThat(root.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(root.errorCode()).isEqualTo(ErrorCode.USER_BUSY.name());
                });
        assertThat(rows)
                .filteredOn(row -> row.parentExecutionId() != null)
                .singleElement()
                .satisfies(
                        step -> assertThat(step.status()).as("흐름이 끝나기 전에 끝난 단계").isEqualTo(ExecutionStatus.SUCCEEDED));
        assertThat(stub().received()).as("Chief 와 들어온 단계의 제출").hasSize(2);
        assertThat(limiter.used(dad.id())).as("흐름이 끝난 뒤 쥔 자리").isZero();
    }

    private DelegationResult delegate(AgentExecution origin, String task) {
        String session = "fos-" + UUID.randomUUID();
        DelegationKey key = DelegationKey.of(origin.profileName(), session, session, "call_" + UUID.randomUUID());
        return delegations.delegate(dad, origin, key, "limit-worker", task);
    }

    private AgentExecution latestExecution() {
        return executions
                .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 1))
                .getFirst();
    }

    private List<AgentExecution> executionsOfDad() {
        return executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 20));
    }

    private Agent agent(String code, String flow) {
        Agent agent = Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                dad.id(),
                Instant.now());
        if (flow != null) {
            agent.assignFlow(flow);
        }
        return agent;
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(
                runId, "session-" + runId, "completed", output, "model", "provider", TokenUsage.empty());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("기다린 신호가 " + WAIT_LIMIT + " 안에 오지 않았다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
