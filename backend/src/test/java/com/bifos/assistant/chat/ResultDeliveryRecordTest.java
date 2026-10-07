package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.DelegationWakeService;
import com.bifos.assistant.chat.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.ResultDeliveryItem;
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
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.TestAutoTurnResultSource;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 turn 이 부모 대화에 넘긴 결과를 전달 묶음과 시도로 남기고, 그 turn 이 끝난 방식으로 닫는지 본다(ADR-075).
 *
 * <p>자동 turn 은 테스트 스레드 밖의 가상 스레드에서 돈다. 검사마다 그 turn 이 끝날 때까지 기다린 뒤 단언한다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class ResultDeliveryRecordTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String TEST_SOURCE = TestAutoTurnResultSource.SOURCE;

    /** 자동 turn 의 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    /** 실행 줄을 만들기 전에 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ContextAssembler contextAssembler;

    /** 시도에 실행 줄을 잇다 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ResultDeliveryRecorder recorder;

    @Autowired
    ChatService chat;

    @Autowired
    DelegationWakeService wake;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    TestAutoTurnResultSource testResults;

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
    private Agent worker;
    private Conversation conversation;
    private AgentExecution root;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        // 이 문맥이 뜰 때 기동 훑기가 앞 검사들이 남긴 결과로 연 turn 이 있을 수 있다. 지우기 전에 끝나기를 기다린다.
        awaitAllIdle();
        stub().reset();
        testResults.reset();
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
        stub().willReturn(result("auto", "completed", "정리한 답"));
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
        testResults.reset();
    }

    @Test
    @DisplayName("부모 turn 이 답을 남기면 묶음은 DELIVERED, 시도는 SUCCEEDED 로 닫히고 실행 줄과 알림 줄을 가리킨다")
    void closesAttemptAsSucceededWhenParentTurnAnswers() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        ResultDelivery delivery = onlyDelivery();
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.attemptCount()).isEqualTo(1);
        assertThat(deliveryItems.findByDeliveryIdOrderByIdAsc(delivery.id()))
                .extracting(ResultDeliveryItem::source, ResultDeliveryItem::resultKey)
                .containsExactly(tuple(ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(done.id())));

        ChatMessage notice = onlyMessage(MessageRole.SYSTEM);
        ChatMessage answer = onlyMessage(MessageRole.ASSISTANT);
        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.deliveryId()).isEqualTo(delivery.id());
        assertThat(attempt.attemptNo()).isEqualTo(1);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.SUCCEEDED);
        assertThat(attempt.executionId()).as("자동 turn 의 실행 줄").isEqualTo(answer.executionId());
        assertThat(attempt.noticeMessageId()).as("알림 줄").isEqualTo(notice.id());
        assertThat(attempt.errorCode()).isNull();
        assertThat(attempt.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("provider 가 실패하면 시도와 묶음이 FAILED 로 남고 같은 결과로 다시 제출하지 않는다")
    void closesAttemptAsFailedWhenProviderFails() {
        stub().willReturn(result("auto", "failed", null));
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.errorCode()).isEqualTo("HERMES_RUN_FAILED");
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(onlyDelivery().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("도착 알림 줄 저장은 그대로 남는다")
                .isNotNull();
        assertThat(stub().received()).hasSize(1);

        // 실패 유예 30초를 기다리지 않고 다시 깨운다. 전했다고 적힌 결과라 다시 제출하지 않는다.
        wake.tryWake(conversation.id());
        awaitIdle(conversation.id());

        assertThat(stub().received()).as("다시 깨워도 대역 Hermes 제출 수").hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
        assertThat(attempts.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("실행 줄을 만들기 전에 실패하면 시도는 실행 줄 없이 FAILED 로 닫히고 알림 줄은 남는다")
    void closesAttemptWithoutExecutionWhenTurnFailsBeforeExecution() {
        doThrow(new IllegalStateException("문맥을 조립하지 못했다"))
                .doCallRealMethod()
                .when(contextAssembler)
                .assemble(any(), any());
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.executionId()).isNull();
        assertThat(attempt.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(onlyDelivery().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.noticeMessageId())
                .as("남은 알림 줄")
                .isEqualTo(onlyMessage(MessageRole.SYSTEM).id());
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("사용자가 부모 turn 을 중지하면 시도와 묶음이 STOPPED 로 남는다")
    void closesAttemptAsStoppedWhenUserStopsParentTurn() {
        stub().beforeAwait(() -> chat.stop(dad, latestExecution().id()));
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.STOPPED);
        assertThat(attempt.errorCode()).isNull();
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(onlyDelivery().status()).isEqualTo(DeliveryStatus.STOPPED);
    }

    @Test
    @DisplayName("중지를 확정한 뒤 완료 대기가 던지면 실행 줄은 CANCELLED, 시도와 묶음은 STOPPED 로 남는다")
    void closesAttemptAsStoppedWhenAwaitThrowsAfterStopConfirmed() {
        stub().beforeAwait(() -> {
            chat.stop(dad, latestExecution().id());
            throw new IllegalStateException("기다리다 끊겼다");
        });
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.STOPPED);
        assertThat(attempt.errorCode()).isNull();
        assertThat(onlyDelivery().status()).isEqualTo(DeliveryStatus.STOPPED);
        assertThat(attempt.executionId()).isNotNull();
        assertThat(executions.findById(attempt.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    @DisplayName("시도에 실행 줄을 잇다 실패해도 turn 은 답을 남기고 시도는 SUCCEEDED 로 닫힌다")
    void keepsTurnResultWhenAttachingExecutionFails() {
        doThrow(new IllegalStateException("시도를 잇지 못했다"))
                .doCallRealMethod()
                .when(recorder)
                .attachExecution(any(), any());
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");

        finished(done);
        awaitIdle(conversation.id());

        assertThat(onlyMessage(MessageRole.ASSISTANT).content()).isEqualTo("정리한 답");
        ResultDeliveryAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.SUCCEEDED);
        assertThat(attempt.executionId()).isNull();
        assertThat(onlyDelivery().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("위임 결과와 다른 출처의 결과를 함께 전하면 한 묶음에 위임 결과부터 차례로 항목을 둔다")
    void putsDelegationAndOtherSourceResultsIntoOneDeliveryInOrder() {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        String key = UUID.randomUUID().toString();
        testResults.offer(conversation.id(), new AutoTurnResult(key, "승인한 「메모」 실행이 끝났어요", "메모를 남겼다"));

        finished(done);
        awaitIdle(conversation.id());

        ResultDelivery delivery = onlyDelivery();
        assertThat(deliveryItems.findByDeliveryIdOrderByIdAsc(delivery.id()))
                .extracting(ResultDeliveryItem::source, ResultDeliveryItem::resultKey)
                .containsExactly(
                        tuple(ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(done.id())),
                        tuple(TEST_SOURCE, key));
        assertThat(onlyAttempt().noticeMessageId())
                .as("마지막 알림 줄")
                .isEqualTo(messagesOf(MessageRole.SYSTEM).getLast().id());
        assertThat(testResults.undelivered(conversation.id())).as("전했다고 적힌다").isEmpty();
    }

    @Test
    @DisplayName("같은 결과의 종료 사건이 겹쳐 와도 묶음과 시도와 답과 제출은 하나다")
    void keepsSingleDeliveryWhenSameFinishEventArrivesTwiceConcurrently() throws InterruptedException {
        AgentExecution done = delegated(ExecutionStatus.SUCCEEDED, "조사 결과");
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable publish = () -> {
            try {
                start.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                finished(done);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                errors.add(ex);
            }
        };
        Thread first = Thread.ofPlatform().start(publish);
        Thread second = Thread.ofPlatform().start(publish);
        start.countDown();
        first.join(WAIT_LIMIT.toMillis());
        second.join(WAIT_LIMIT.toMillis());
        awaitIdle(conversation.id());

        assertThat(errors).isEmpty();
        assertThat(deliveries.findAll()).hasSize(1);
        assertThat(attempts.findAll()).hasSize(1);
        assertThat(messagesOf(MessageRole.ASSISTANT)).hasSize(1);
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("같은 결과를 두 묶음에 넣으려 하면 유일 제약으로 실패하고 그 트랜잭션이 되돌아간다")
    void rejectsSameResultInTwoDeliveries() {
        List<DeliveryItemRef> items = List.of(new DeliveryItemRef(ResultDeliveryRecorder.DELEGATION_SOURCE, "4242"));
        Instant now = Instant.now();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    recorder.open(conversation.id(), items, null, now);
                    recorder.open(conversation.id(), items, null, now);
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deliveries.findAll()).as("첫 묶음도 함께 되돌아간다").isEmpty();
        assertThat(deliveryItems.findAll()).isEmpty();
        assertThat(attempts.findAll()).isEmpty();
    }

    private ResultDelivery onlyDelivery() {
        List<ResultDelivery> rows = deliveries.findAll();
        assertThat(rows).as("전달 묶음").hasSize(1);
        return rows.getFirst();
    }

    private ResultDeliveryAttempt onlyAttempt() {
        List<ResultDeliveryAttempt> rows = attempts.findAll();
        assertThat(rows).as("전달 시도").hasSize(1);
        return rows.getFirst();
    }

    private List<ChatMessage> messagesOf(MessageRole role) {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                .filter(message -> message.role() == role)
                .toList();
    }

    private ChatMessage onlyMessage(MessageRole role) {
        List<ChatMessage> rows = messagesOf(role);
        assertThat(rows).as("%s 줄", role).hasSize(1);
        return rows.getFirst();
    }

    /** 가장 최근에 만든 실행 줄이다. 완료를 기다리기 직전에는 자동 turn 의 실행 줄이다. */
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
