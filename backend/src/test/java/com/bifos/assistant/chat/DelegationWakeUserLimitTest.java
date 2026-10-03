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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 위임 결과 자동 turn 이 사용자 실행 한도에 닿았을 때 상한이 있는 재시도를 거는지 본다(ADR-069).
 *
 * <p>한도를 2 로 두고 그 사용자의 RUNNING 자식 줄 둘로 자리를 채운다. 예약은 받은 작업을 모아 두는 대역 스케줄러가 받고,
 * 검사가 곧바로 돌린다. 30초를 실제로 기다리지 않는다.
 */
@SpringBootTest(properties = {"assistant.delegation-wake.enabled=true", "assistant.user-execution.max-running=2"})
@ActiveProfiles("test")
@Import({ChatServiceTest.StubRuntime.class, DelegationWakeUserLimitTest.CapturingSchedulerConfig.class})
class DelegationWakeUserLimitTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final int MAX_BUSY_RETRIES = 10;

    /** 시각 예약만 모아 두고 돌리지 않는다. 그 밖의 예약은 실제 스케줄러로 간다. */
    static final class CapturingTaskScheduler extends ThreadPoolTaskScheduler {
        private final List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        private final List<Instant> startTimes = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            scheduled.add(task);
            startTimes.add(startTime);
            return null;
        }

        /** 모아 둔 작업을 꺼내 비운다. */
        List<Runnable> drain() {
            List<Runnable> tasks = new ArrayList<>(scheduled);
            scheduled.clear();
            return tasks;
        }

        void clear() {
            scheduled.clear();
            startTimes.clear();
        }
    }

    @TestConfiguration
    static class CapturingSchedulerConfig {
        @Bean
        @Primary
        CapturingTaskScheduler capturingTaskScheduler() {
            return new CapturingTaskScheduler();
        }
    }

    /** 자동 turn 의 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    @Autowired
    CapturingTaskScheduler scheduler;

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
        scheduler.clear();
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

        assertThat(scheduler.startTimes)
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
    @DisplayName("한도에 닿은 거절이 이어지면 10번까지만 재시도를 예약한다")
    void schedulesAtMostTenRetriesWhileRejectionsContinue() {
        fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        int ran = 0;
        for (List<Runnable> due = scheduler.drain(); !due.isEmpty(); due = scheduler.drain()) {
            for (Runnable task : due) {
                task.run();
                ran++;
            }
            assertThat(ran).as("돌린 재시도 수").isLessThanOrEqualTo(MAX_BUSY_RETRIES);
        }

        assertThat(ran).as("예약된 재시도 수").isEqualTo(MAX_BUSY_RETRIES);
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("전했다는 표시")
                .isNull();
        assertThat(stub().received()).as("Hermes 제출").isEmpty();
    }

    @Test
    @DisplayName("자리가 난 뒤 예약한 재시도가 돌면 결과를 전한다")
    void deliversResultWhenScheduledRetryRunsAfterSlotFrees() {
        List<AgentExecution> fillers = fillSlots();
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        finished(done);
        List<Runnable> due = scheduler.drain();
        assertThat(due).as("예약한 재시도").hasSize(1);

        executions.deleteAll(fillers);
        due.getFirst().run();
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
