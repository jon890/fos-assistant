package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
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
 * 위임 자식이 사용자 실행 한도에 닿으면 기다리지 않고 거절되는지 본다(ADR-069).
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
        return (StubHermesRunsClient)hermes;
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
        agents.save(agent("limit-chief"));
        agents.save(agent("limit-worker"));
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
            .extracting(command -> command.input().contains(PARENT_MARK) || command.input().contains(CHILD_MARK))
            .containsOnly(true)
            .hasSize(2);
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


    private Agent agent(String code) {
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
