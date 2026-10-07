package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.CapturingTaskScheduler;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.SmallExecutionLimit;
import com.bifos.assistant.testsupport.WakeRetryThreads;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 위임 결과 자동 turn 이 사용자 실행 한도에 닿았을 때 상한이 있는 재시도를 거는지 본다(ADR-069).
 *
 * <p>한도를 2 로 두고 그 사용자의 RUNNING 자식 줄 둘로 자리를 채운다. 예약은 받은 작업을 모아 두는 대역 스케줄러가 받고,
 * 검사가 곧바로 돌린다. 30초를 실제로 기다리지 않는다. 예약한 작업은 가상 스레드를 띄우고 돌아오므로 검사는 그 스레드가
 * 끝날 때까지 기다린다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
@SmallExecutionLimit
class DelegationWakeUserLimitTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final int MAX_BUSY_RETRIES = 10;

    /** 자동 turn 의 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    @Autowired
    CapturingTaskScheduler scheduler;

    @Autowired
    WakeRetryThreads retryThreads;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ApplicationEventPublisher publisher;

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

    private CurrentUser dad;
    private Agent worker;
    private Conversation conversation;
    private AgentExecution root;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        awaitAllIdle();
        stub().reset();
        scheduler.capture();
        retryThreads.start();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        Agent chief = agents.save(agent("dad", "비서", user.id()));
        worker = agents.save(agent("worker", "조사원", user.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        stub().willReturn(HermesRunResult.of(
                "auto", "session", "completed", "정리한 답", "model", "provider", TokenUsage.empty()));
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
    }

    @Test
    @DisplayName("한도에 닿아 자동 turn 이 거절되면 결과를 전하지 않은 채 남기고 30초 뒤 재시도를 예약한다")
    void leavesResultUndeliveredAndSchedulesRetryWhenRejectedByUserLimit() {
        fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        Instant before = Instant.now();

        finished(done);

        assertThat(scheduler.startTimes())
                .as("예약한 재시도")
                .singleElement()
                .satisfies(at -> assertThat(at).as("예약 시각").isAfterOrEqualTo(before.plus(Duration.ofSeconds(30))));
        assertThat(turns.markOf(conversation.id()).running()).as("열린 turn").isFalse();
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("전했다는 표시")
                .isNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(stub().received()).as("Hermes 제출").isEmpty();
    }

    @Test
    @DisplayName("한도에 닿은 거절이 10번을 넘으면 5분 간격으로 이어 예약하고 자리가 나면 결과를 전한다")
    void keepsRetryingAtLongIntervalAfterTenRejections() throws InterruptedException {
        List<AgentExecution> fillers = fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        for (int ran = 0; ran < MAX_BUSY_RETRIES; ran++) {
            List<Runnable> due = scheduler.drain();
            assertThat(due).as("%d번째 짧은 재시도", ran + 1).hasSize(1);
            runRetry(due.getFirst());
        }
        List<Instant> times = scheduler.startTimes();
        Instant shortRetry = times.get(times.size() - 2);
        Instant longRetry = times.getLast();
        assertThat(longRetry)
                .as("10번을 넘긴 뒤의 예약 시각")
                .isAfterOrEqualTo(Instant.now().plus(Duration.ofMinutes(5)).minus(WAIT_LIMIT));
        assertThat(shortRetry).as("10번째 예약 시각").isBefore(Instant.now().plus(Duration.ofMinutes(1)));

        List<Runnable> slow = scheduler.drain();
        assertThat(slow).as("한 대화에 걸린 예약").hasSize(1);
        runRetry(slow.getFirst());
        List<Runnable> again = scheduler.drain();
        assertThat(again).as("여전히 막히면 다시 건 예약").hasSize(1);
        assertThat(scheduler.startTimes().getLast())
                .as("이어 건 예약도 5분 간격")
                .isAfterOrEqualTo(Instant.now().plus(Duration.ofMinutes(5)).minus(WAIT_LIMIT));
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("막혀 있는 동안 전했다는 표시")
                .isNull();
        assertThat(stub().received()).as("막혀 있는 동안 Hermes 제출").isEmpty();

        executions.deleteAll(fillers);
        runRetry(again.getFirst());
        awaitIdle(conversation.id());
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("자리가 난 뒤 전했다는 표시")
                .isNotNull();
    }

    @Test
    @DisplayName("자리가 난 뒤 예약한 재시도가 돌면 결과를 전한다")
    void deliversResultWhenScheduledRetryRunsAfterSlotFrees() throws InterruptedException {
        List<AgentExecution> fillers = fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        finished(done);
        List<Runnable> due = scheduler.drain();
        assertThat(due).as("예약한 재시도").hasSize(1);

        executions.deleteAll(fillers);
        runRetry(due.getFirst());
        awaitIdle(conversation.id());

        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("전했다는 표시")
                .isNotNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(scheduler.drain()).as("결과를 전한 뒤의 예약").isEmpty();
        assertThat(limiter.used(dad.id())).as("자동 turn 이 끝난 뒤 쥔 자리").isZero();
    }

    @Test
    @DisplayName("예약 뒤 다른 실패가 시각을 새로 적었으면 재시도가 그 시각을 지우지 않는다")
    void keepsNewerFailureTimeWhenEarlierRetryRuns() throws InterruptedException {
        List<AgentExecution> fillers = fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        finished(done);
        List<Runnable> first = scheduler.drain();
        assertThat(first).as("처음 예약한 재시도").hasSize(1);
        // 처음 재시도도 한도에 닿아 거절된다. 새 실패 시각을 적고 다음 재시도를 예약한다.
        runRetry(first.getFirst());
        List<Runnable> second = scheduler.drain();
        assertThat(second).as("다시 거절된 뒤 예약한 재시도").hasSize(1);
        executions.deleteAll(fillers);

        // 실제 시계를 앞당길 수 없어, 새 시각을 적은 뒤 앞 예약이 닿는 순서를 처음 작업을 한 번 더 돌려 만든다.
        runRetry(first.getFirst());
        awaitIdle(conversation.id());

        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("새 실패의 유예 안에 앞 재시도가 전한 표시")
                .isNull();
        assertThat(stub().received()).as("새 실패의 유예 안의 Hermes 제출").isEmpty();
        assertThat(scheduler.drain()).as("유예 안에 막힌 재시도가 더 건 예약").isEmpty();

        runRetry(second.getFirst());
        awaitIdle(conversation.id());

        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("새 시각을 적은 쪽의 재시도가 전한 표시")
                .isNotNull();
    }

    /** 예약한 재시도 작업을 돌리고, 그 작업이 띄운 스레드가 사건을 내고 끝날 때까지 기다린다. */
    private void runRetry(Runnable task) throws InterruptedException {
        task.run();
        retryThreads.awaitOne(WAIT_LIMIT);
    }

    /** 대화 turn 의 루트가 아닌 RUNNING 줄 둘로 dad 의 자리를 채운다. 대화가 없어 결과로 전해지지 않는다. */
    private List<AgentExecution> fillSlots() {
        List<AgentExecution> fillers = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            fillers.add(executions.save(AgentExecution.builder()
                    .userId(dad.id())
                    .agentId(worker.id())
                    .parentExecutionId(root.id())
                    .rootExecutionId(root.id())
                    .profileName("worker")
                    .costMode(CostMode.SUBSCRIPTION)
                    .status(ExecutionStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build()));
        }
        assertThat(limiter.used(dad.id())).as("채운 뒤 dad 가 쥔 자리").isEqualTo(2);
        return fillers;
    }

    private void finished(AgentExecution execution) {
        publisher.publishEvent(new DelegationFinished(conversation.id(), execution.id()));
    }

    /** 대화 turn 이 직접 맡긴 위임 실행 줄을 만든다. */
    private AgentExecution delegated(ExecutionStatus status, String output) {
        AgentExecution execution = AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.id())
                .delegationKey(UUID.randomUUID().toString())
                .profileName("worker")
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(Instant.now())
                .build();
        execution.recordOutput(output);
        return executions.save(execution);
    }

    private static Agent agent(String code, String name, Long ownerId) {
        return Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                ownerId,
                Instant.now());
    }

    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitIdle(it.id()));
    }

    /** 그 대화에 도는 turn 이 없어질 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private void awaitIdle(Long conversationId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (turns.markOf(conversationId).running()) {
            if (System.nanoTime() > deadline) {
                fail("대화 %d 의 turn 이 %s 안에 끝나지 않았다", conversationId, WAIT_LIMIT);
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
