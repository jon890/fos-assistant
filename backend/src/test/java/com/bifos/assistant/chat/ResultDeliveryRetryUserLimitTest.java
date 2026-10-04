package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 결과 다시 전달이 사용자 실행 한도에 닿으면 묶음을 바꾸지 않고 다시 시도를 예약하지 않는지 본다(ADR-069, ADR-070).
 *
 * <p>한도를 2 로 두고 그 사용자의 RUNNING 자식 줄 둘로 자리를 채운다. 구성은 {@link DelegationWakeUserLimitTest} 와 같게
 * 둔다. 같은 Spring 컨텍스트를 써서 컨텍스트 수를 늘리지 않고, 예약은 그 검사의 대역 스케줄러가 받는다.
 */
@SpringBootTest(properties = {"assistant.delegation-wake.enabled=true", "assistant.user-execution.max-running=2"})
@ActiveProfiles("test")
@Import({
    ChatServiceTest.StubRuntime.class,
    DelegationWakeUserLimitTest.CapturingSchedulerConfig.class,
    DelegationWakeUserLimitTest.RetryThreadsConfig.class
})
class ResultDeliveryRetryUserLimitTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 한도로 거절한 뒤 제출이 없는지 지켜보는 시간이다. 거절은 그 자리에서 끝나므로 늦은 제출이 있다면 이 안에 보인다. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

    @MockitoBean
    HermesRunEventStream eventStream;

    @Autowired
    DelegationWakeUserLimitTest.CapturingTaskScheduler scheduler;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    ChatService chat;

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
    ResultDeliveryRepository deliveries;

    @Autowired
    ResultDeliveryItemRepository deliveryItems;

    @Autowired
    ResultDeliveryAttemptRepository attempts;

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
        deliveryItems.deleteAll();
        attempts.deleteAll();
        deliveries.deleteAll();
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
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
    }

    @Test
    @DisplayName("한도에 닿으면 USER_BUSY 이고 묶음은 FAILED 그대로이며 제출과 예약이 없고, 자리가 나면 다시 전달한다")
    void rejectsRetryAtUserLimitWithoutTouchingDelivery() throws InterruptedException {
        stub().willReturn(result("auto", "failed", null));
        publisher.publishEvent(
                new DelegationFinished(conversation.id(), delegated().id()));
        awaitIdle(conversation.id());
        ResultDelivery delivery = onlyDelivery();
        assertThat(delivery.status()).as("첫 시도가 실패한 묶음").isEqualTo(DeliveryStatus.FAILED);
        stub().willReturn(result("retry", "completed", "다시 정리한 답"));
        scheduler.clear();
        List<AgentExecution> fillers = fillSlots();

        assertThatThrownBy(() -> chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {}))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.USER_BUSY));
        Thread.sleep(QUIET_PERIOD.toMillis());

        ResultDelivery rejected = deliveries.findById(delivery.id()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(rejected.attemptCount()).isEqualTo(1);
        assertThat(attempts.findAll()).hasSize(1);
        assertThat(stub().received()).as("첫 자동 turn 말고 Hermes 제출").hasSize(1);
        assertThat(scheduler.drain()).as("한도로 거절한 다시 전달의 예약").isEmpty();

        executions.deleteAll(fillers);
        chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {});

        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(limiter.used(dad.id())).as("다시 전달이 끝난 뒤 쥔 자리").isZero();
    }

    private ResultDelivery onlyDelivery() {
        List<ResultDelivery> rows = deliveries.findAll();
        assertThat(rows).as("전달 묶음").hasSize(1);
        return rows.getFirst();
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

    /** 대화 turn 이 직접 맡긴 끝난 위임 실행 줄을 만든다. */
    private AgentExecution delegated() {
        AgentExecution execution = AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.id())
                .delegationKey(UUID.randomUUID().toString())
                .profileName("worker")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build();
        execution.recordOutput("조사 결과");
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

    private static HermesRunResult result(String runId, String status, String output) {
        return HermesRunResult.of(runId, "session", status, output, "model", "provider", TokenUsage.empty());
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
