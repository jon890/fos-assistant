package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.application.RunningTurn;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnIntent;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.application.model.DeliveryState;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 실패하거나 중지한 결과 전달을 다시 전달하면 저장된 결과만 다시 읽어 부모에 넘기는지 본다(ADR-075).
 *
 * <p>계약은 {@code docs/backend/agent-delegation.md} 의 「다시 전달할 때」 와 「다시 전달이 갈리는 지점」 이다. 첫 자동 turn
 * 은 테스트 스레드 밖의 가상 스레드에서 돌아 끝날 때까지 기다린다. 다시 전달은 테스트 스레드에서 부른다.
 *
 * <p>구성은 {@link ResultDeliveryRecordTest} 와 같게 둔다. 같은 Spring 컨텍스트를 써서 컨텍스트 수를 늘리지 않는다.
 */
@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")
@ActiveProfiles("test")
@Import({ChatServiceTest.StubRuntime.class, ResultDeliveryRecordTest.TestResults.class})
class ResultDeliveryRetryTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String TEST_SOURCE = "TEST_SOURCE";

    /** {@code ChatService} 의 다시 전달 알림 줄이다. 상수는 package-private 이라 글로 견준다. */
    private static final String RETRY_NOTICE = "맡긴 일의 결과를 다시 전해요";

    @MockitoBean
    HermesRunEventStream eventStream;

    /** {@link ResultDeliveryRecordTest} 와 같은 컨텍스트를 쓰려고 같게 감싼다. 이 검사에서는 실제 동작 그대로다. */
    @MockitoSpyBean
    ContextAssembler contextAssembler;

    @MockitoSpyBean
    ResultDeliveryRecorder recorder;

    @Autowired
    ChatService chat;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ResultDeliveryRecordTest.TestResultSource testResults;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

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
    private Agent chief;
    private Agent worker;
    private Conversation conversation;
    private AgentExecution root;
    private final List<ChatEvent> events = new CopyOnWriteArrayList<>();

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        awaitAllIdle();
        stub().reset();
        testResults.clear();
        events.clear();
        deliveryItems.deleteAll();
        attempts.deleteAll();
        deliveries.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        dad = currentUser(user);
        chief = agents.save(agent("dad", "비서", user.id()));
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
        stub().releaseSubmits();
        awaitAllIdle();
        testResults.clear();
    }

    @Test
    @DisplayName("provider 가 실패한 전달을 다시 전달하면 같은 입력을 부모 profile 에만 보내고 묶음은 DELIVERED 가 된다")
    void retriesFailedDeliveryWithSameInputToParentProfileOnly() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        ResultDelivery delivery = failedDelivery(done);
        AgentExecution resultBefore = executions.findById(done.id()).orElseThrow();

        chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add);

        assertThat(events).extracting(ChatEvent::type).containsExactly("system", "started", "done");
        assertThat(history())
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(
                        tuple(MessageRole.SYSTEM, "조사원 에이전트의 결과가 도착했어요"),
                        tuple(MessageRole.SYSTEM, RETRY_NOTICE),
                        tuple(MessageRole.ASSISTANT, "다시 정리한 답"));
        ResultDelivery after = deliveries.findById(delivery.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(after.attemptCount()).isEqualTo(2);
        List<ResultDeliveryAttempt> tries = attemptsOf(delivery.id());
        assertThat(tries)
                .extracting(ResultDeliveryAttempt::attemptNo, ResultDeliveryAttempt::status)
                .containsExactly(tuple(1, DeliveryAttemptStatus.FAILED), tuple(2, DeliveryAttemptStatus.SUCCEEDED));
        ChatMessage retryNotice = messagesOf(MessageRole.SYSTEM).getLast();
        ChatMessage answer = messagesOf(MessageRole.ASSISTANT).getFirst();
        assertThat(tries.get(1).noticeMessageId()).as("다시 전달의 알림 줄").isEqualTo(retryNotice.id());
        assertThat(tries.get(1).executionId()).as("다시 전달 turn 의 실행 줄").isEqualTo(answer.executionId());

        List<HermesRunCommand> received = stub().received();
        assertThat(received).hasSize(2);
        assertThat(received.get(1).input())
                .as("다시 전달의 입력")
                .isEqualTo(received.get(0).input());
        assertThat(received.get(0).instructions()).endsWith(TurnIntent.DELEGATION_RESULTS_INSTRUCTION);
        assertThat(received.get(1).instructions()).endsWith(TurnIntent.DELIVERY_RETRY_INSTRUCTION);
        assertThat(received).extracting(HermesRunCommand::profileName).containsOnly("dad");

        AgentExecution resultAfter = executions.findById(done.id()).orElseThrow();
        assertThat(resultAfter.status()).isEqualTo(resultBefore.status());
        assertThat(resultAfter.outputText()).isEqualTo(resultBefore.outputText());
        assertThat(resultAfter.resultDeliveredAt()).isEqualTo(resultBefore.resultDeliveredAt());
        assertThat(conversations.findById(conversation.id()).orElseThrow().autoTurnCount())
                .as("사람이 요청한 turn 이라 새로 센다")
                .isZero();
    }

    @Test
    @DisplayName("위임 결과와 다른 출처의 결과가 든 묶음을 다시 전달하면 두 결과를 첫 시도와 같은 순서로 다시 읽는다")
    void retriesDeliveryWithDelegationAndOtherSourceResultsInSameOrder() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        testResults.offer(
                conversation.id(), new AutoTurnResult(UUID.randomUUID().toString(), "승인한 「메모」 실행이 끝났어요", "메모를 남겼다"));
        ResultDelivery delivery = failedDelivery(done);

        chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add);

        List<HermesRunCommand> received = stub().received();
        assertThat(received).hasSize(2);
        assertThat(received.get(1).input()).isEqualTo(received.get(0).input());
        assertThat(received.get(1).input()).contains("조사 결과\n\n메모를 남겼다");
        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("사용자가 중지한 전달도 다시 전달하면 답을 남기고 묶음이 DELIVERED 가 된다")
    void retriesStoppedDelivery() {
        stub().willReturn(result("auto", "completed", "정리한 답"));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution().id()));
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        finished(done);
        awaitIdle(conversation.id());
        ResultDelivery delivery = onlyDelivery();
        assertThat(delivery.status()).as("첫 시도를 중지한 묶음").isEqualTo(DeliveryStatus.STOPPED);
        stub().beforeAwait(() -> {});
        stub().willReturn(result("retry", "completed", "다시 정리한 답"));

        chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add);

        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(attemptsOf(delivery.id()))
                .extracting(ResultDeliveryAttempt::status)
                .containsExactly(DeliveryAttemptStatus.STOPPED, DeliveryAttemptStatus.SUCCEEDED);
        assertThat(messagesOf(MessageRole.ASSISTANT).getLast().content()).isEqualTo("다시 정리한 답");
    }

    @Test
    @DisplayName("이미 DELIVERED 인 묶음은 DELIVERY_NOT_RETRYABLE 이고 시도와 알림 줄이 늘지 않는다")
    void rejectsRetryOfDeliveredDelivery() {
        stub().willReturn(result("auto", "completed", "정리한 답"));
        finished(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        awaitIdle(conversation.id());
        ResultDelivery delivery = onlyDelivery();
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.DELIVERED);

        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add),
                ErrorCode.DELIVERY_NOT_RETRYABLE);

        assertThat(attempts.findAll()).hasSize(1);
        assertThat(messagesOf(MessageRole.SYSTEM)).hasSize(1);
        assertThat(stub().received()).hasSize(1);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("다시 전달이 도는 동안 다시 누르면 묶음이 이미 DELIVERING 이라 거절되고 시도와 답은 하나씩만 더해진다")
    void rejectsSecondRetryWhileFirstRetryRuns() throws InterruptedException {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        stub().holdSubmits();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread first = Thread.ofPlatform().start(() -> {
            try {
                chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add);
            } catch (RuntimeException ex) {
                errors.add(ex);
            }
        });
        awaitReceived(2);

        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {}),
                ErrorCode.DELIVERY_NOT_RETRYABLE);
        ChatMessage retryNotice = messagesOf(MessageRole.SYSTEM).getLast();
        assertThat(retryNotice.content()).isEqualTo(RETRY_NOTICE);
        assertThat(chat.deliveryStates(conversation.id()))
                .as("붙잡은 동안 이력이 보는 상태")
                .containsEntry(retryNotice.id(), new DeliveryState(delivery.id(), DeliveryStatus.DELIVERING));
        RunningTurn running = chat.running(dad, conversation.id());
        assertThat(running.running()).isTrue();
        assertThat(running.executionId())
                .as("재접속한 창이 보는 실행")
                .isEqualTo(latestExecution().id());

        stub().releaseSubmits();
        first.join(WAIT_LIMIT.toMillis());
        awaitIdle(conversation.id());

        assertThat(errors).isEmpty();
        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {}),
                ErrorCode.DELIVERY_NOT_RETRYABLE);
        assertThat(attempts.findAll()).hasSize(2);
        assertThat(messagesOf(MessageRole.ASSISTANT)).hasSize(1);
        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("같은 대화에 다른 turn 이 돌면 CONVERSATION_BUSY 이고 묶음은 FAILED 그대로다")
    void rejectsRetryWhileAnotherTurnRuns() throws InterruptedException {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        stub().holdSubmits();
        Thread userTurn = Thread.ofPlatform().start(() -> {
            try {
                chat.stream(dad, conversation.id(), "새 질문", null, event -> {});
            } catch (RuntimeException ex) {
                // 붙잡은 사용자 turn 의 결과는 이 검사가 보지 않는다.
            }
        });
        awaitReceived(2);

        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add),
                ErrorCode.CONVERSATION_BUSY);

        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempts.findAll()).hasSize(1);
        assertThat(events).isEmpty();
        stub().releaseSubmits();
        userTurn.join(WAIT_LIMIT.toMillis());
    }

    @Test
    @DisplayName("잠금 뒤 다른 요청이 먼저 묶음을 바꿨으면 조건부 update 가 거절하고 그 트랜잭션의 알림 줄도 되돌아간다")
    void rollsBackNoticeWhenAnotherRequestClaimedFirst() {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        transactions.executeWithoutResult(
                status -> deliveries.changeStatus(delivery.id(), DeliveryStatus.DELIVERING, Instant.now()));
        int linesBefore = history().size();

        assertCode(
                () -> transactions.executeWithoutResult(status -> {
                    messages.save(ChatMessage.fromSystem(conversation.id(), RETRY_NOTICE, Instant.now()));
                    recorder.claimRetry(delivery.id(), Instant.now());
                }),
                ErrorCode.DELIVERY_NOT_RETRYABLE);

        assertThat(history()).as("되돌아간 알림 줄").hasSize(linesBefore);
        ResultDelivery after = deliveries.findById(delivery.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(after.attemptCount()).isEqualTo(1);
        assertThat(attempts.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("남의 대화이면 CONVERSATION_NOT_FOUND, 같은 사용자의 다른 대화 번호이면 DELIVERY_NOT_FOUND 다")
    void hidesDeliveryOfOtherConversation() {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        CurrentUser mom =
                currentUser(users.save(AppUser.of("mom@example.com", "mom", 1L, UserRole.MEMBER, Instant.now())));
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "다른 대화", chief.id(), Instant.now()));

        assertCode(
                () -> chat.retryDelivery(mom, conversation.id(), delivery.id(), events::add),
                ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(() -> chat.retryDelivery(dad, other.id(), delivery.id(), events::add), ErrorCode.DELIVERY_NOT_FOUND);

        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("대화의 에이전트를 더 읽을 수 없으면 AGENT_NOT_FOUND, 꺼졌으면 AGENT_DISABLED 이고 묶음과 제출은 그대로다")
    void rejectsRetryWhenAgentIsNoLongerUsable() {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        AppUser mom = users.save(AppUser.of("mom@example.com", "mom", 1L, UserRole.MEMBER, Instant.now()));
        int submitted = stub().received().size();

        chief.changeAccess(true, AgentVisibility.PRIVATE, mom.id());
        chief = agents.save(chief);
        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add),
                ErrorCode.AGENT_NOT_FOUND);

        chief.changeAccess(false, AgentVisibility.PRIVATE, dad.id());
        chief = agents.save(chief);
        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add), ErrorCode.AGENT_DISABLED);

        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempts.findAll()).hasSize(1);
        assertThat(stub().received()).as("Hermes 제출").hasSize(submitted);
    }

    @Test
    @DisplayName("재기동으로 중단돼 기동 정리가 닫은 전달을 다시 전달하면 시도 2 가 SUCCEEDED 로 닫힌다")
    void retriesDeliveryInterruptedByRestart() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        Instant before = Instant.now().minusSeconds(60);
        deliveryWriter.markResultDelivered(done.id(), before);
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "조사원 에이전트의 결과가 도착했어요", before));
        Long deliveryId = recorder.open(
                        conversation.id(),
                        List.of(new DeliveryItemRef(
                                ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(done.id()))),
                        notice.id(),
                        before)
                .deliveryId();
        assertThat(recorder.closeLeftovers(Instant.now())).isEqualTo(1);
        assertThat(attemptsOf(deliveryId))
                .extracting(ResultDeliveryAttempt::status, ResultDeliveryAttempt::errorCode)
                .containsExactly(tuple(DeliveryAttemptStatus.FAILED, ResultDeliveryRecorder.INTERRUPTED));
        stub().willReturn(result("retry", "completed", "다시 정리한 답"));

        chat.retryDelivery(dad, conversation.id(), deliveryId, events::add);

        assertThat(attemptsOf(deliveryId))
                .extracting(ResultDeliveryAttempt::attemptNo, ResultDeliveryAttempt::status)
                .containsExactly(tuple(1, DeliveryAttemptStatus.FAILED), tuple(2, DeliveryAttemptStatus.SUCCEEDED));
        assertThat(deliveries.findById(deliveryId).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("항목의 위임 실행 줄이 지워졌으면 넘길 결과가 없어 DELIVERY_NOT_RETRYABLE 이고 묶음은 그대로다")
    void rejectsRetryWhenResultRowIsGone() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        ResultDelivery delivery = failedDelivery(done);
        executions.deleteById(done.id());

        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add),
                ErrorCode.DELIVERY_NOT_RETRYABLE);

        ResultDelivery after = deliveries.findById(delivery.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(after.attemptCount()).isEqualTo(1);
        assertThat(messagesOf(MessageRole.SYSTEM)).hasSize(1);
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("항목의 출처 이름에 맞는 구현이 없으면 그 항목을 빼고, 남은 것이 없으면 DELIVERY_NOT_RETRYABLE 이다")
    void rejectsRetryWhenNoSourceReadsTheItems() {
        Instant before = Instant.now().minusSeconds(60);
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "결과가 도착했어요", before));
        Long deliveryId = recorder.open(
                        conversation.id(), List.of(new DeliveryItemRef("UNKNOWN_SOURCE", "key-1")), notice.id(), before)
                .deliveryId();
        recorder.closeLeftovers(Instant.now());

        assertCode(
                () -> chat.retryDelivery(dad, conversation.id(), deliveryId, events::add),
                ErrorCode.DELIVERY_NOT_RETRYABLE);

        assertThat(deliveries.findById(deliveryId).orElseThrow().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("이력의 묶음 상태는 실패 뒤 첫 알림 줄에, 다시 전달 뒤에는 다시 전달의 알림 줄에만 붙는다")
    void movesDeliveryStateToLatestNotice() {
        ResultDelivery delivery = failedDelivery(delegated(ExecutionStatus.SUCCEEDED, "조사 결과"));
        ChatMessage firstNotice = messagesOf(MessageRole.SYSTEM).getFirst();

        assertThat(chat.deliveryStates(conversation.id()))
                .isEqualTo(Map.of(firstNotice.id(), new DeliveryState(delivery.id(), DeliveryStatus.FAILED)));

        chat.retryDelivery(dad, conversation.id(), delivery.id(), events::add);

        ChatMessage retryNotice = messagesOf(MessageRole.SYSTEM).getLast();
        assertThat(retryNotice.content()).isEqualTo(RETRY_NOTICE);
        assertThat(chat.deliveryStates(conversation.id()))
                .isEqualTo(Map.of(retryNotice.id(), new DeliveryState(delivery.id(), DeliveryStatus.DELIVERED)));
    }

    @Test
    @DisplayName("마지막 시도에 알림 줄이 없는 묶음은 이력의 묶음 상태에서 빠진다")
    void leavesOutDeliveryWhoseLatestAttemptHasNoNotice() {
        recorder.open(
                conversation.id(),
                List.of(new DeliveryItemRef(ResultDeliveryRecorder.DELEGATION_SOURCE, "4242")),
                null,
                Instant.now());

        assertThat(chat.deliveryStates(conversation.id())).isEmpty();
    }

    /** 첫 자동 turn 을 provider 실패로 끝내 FAILED 묶음을 만든다. 다시 전달 때는 대역 Hermes 가 완료로 답한다. */
    private ResultDelivery failedDelivery(AgentExecution done) {
        stub().willReturn(result("auto", "failed", null));
        finished(done);
        awaitIdle(conversation.id());
        ResultDelivery delivery = onlyDelivery();
        assertThat(delivery.status()).as("첫 시도가 실패한 묶음").isEqualTo(DeliveryStatus.FAILED);
        stub().willReturn(result("retry", "completed", "다시 정리한 답"));
        return delivery;
    }

    private ResultDelivery onlyDelivery() {
        List<ResultDelivery> rows = deliveries.findAll();
        assertThat(rows).as("전달 묶음").hasSize(1);
        return rows.getFirst();
    }

    private List<ResultDeliveryAttempt> attemptsOf(Long deliveryId) {
        return attempts.findAll().stream()
                .filter(attempt -> attempt.deliveryId().equals(deliveryId))
                .sorted(Comparator.comparingInt(ResultDeliveryAttempt::attemptNo))
                .toList();
    }

    private List<ChatMessage> history() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    private List<ChatMessage> messagesOf(MessageRole role) {
        return history().stream().filter(message -> message.role() == role).toList();
    }

    /** 가장 최근에 만든 실행 줄이다. */
    private AgentExecution latestExecution() {
        return executions
                .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 1))
                .getFirst();
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
                .rootExecutionId(root.treeRootId())
                .delegationKey(UUID.randomUUID().toString())
                .profileName("worker")
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(Instant.now())
                .build();
        execution.recordOutput(output);
        return executions.save(execution);
    }

    private static CurrentUser currentUser(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
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

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    /** 대역 Hermes 가 그만큼 제출을 받을 때까지 기다린다. 붙잡은 제출도 받은 것으로 센다. */
    private void awaitReceived(int count) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (stub().received().size() < count) {
            if (System.nanoTime() > deadline) {
                fail(
                        "Hermes 가 %d 번 받지 못했다 received=%d",
                        count, stub().received().size());
            }
            pause();
        }
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
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("기다리는 중에 끊겼다");
        }
    }
}
