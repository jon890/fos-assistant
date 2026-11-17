package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.attachment.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.domain.type.SteerState;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.NextTurnDispatcher;
import com.bifos.assistant.chat.nextturn.application.PendingMessageService;
import com.bifos.assistant.chat.nextturn.application.SteerLedger;
import com.bifos.assistant.chat.recovery.application.RecoveredRunRecorder;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.turn.application.dto.SteerSettlement;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.hermes.infra.HermesRunEventStream;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.PendingQueueFixtures;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import java.sql.Timestamp;

/**
 * turn 이 끝날 때 steer 말풍선을 정리하고 돌아온 글을 다음 차례로, 모르는 글을 멈춘 대기 줄로 보내는지 본다(ADR-20261116 / run-steer).
 */
@BackendIntegrationTest
class SteerSettleTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    @Autowired
    PendingMessageService pending;

    @Autowired
    ChatService chat;

    @Autowired
    NextTurnDispatcher dispatcher;

    @Autowired
    SteerLedger ledger;

    @Autowired
    RecoveredRunRecorder recorder;

    @Autowired
    TurnCancellation turns;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    PendingMessageStore pendingRows;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private CurrentUser dad;
    private Conversation conversation;
    private CountDownLatch release;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        awaitAllIdle();
        stub().reset();
        PendingQueueFixtures.clearAll(jdbc);
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        dad = signedUp("dad@example.com", "dad");
        Agent chief = agents.save(agent("dad", "비서", dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        stub().willReturn(result("completed", null));
        release = new CountDownLatch(1);
    }


    @AfterEach
    void tearDown() {
        release.countDown();
        stub().beforeAwait(() -> {
        });
        awaitAllIdle();
        PendingQueueFixtures.clearAll(jdbc);
    }


    @Test
    @DisplayName("돌아온 글은 그 말풍선만 다음 차례로 넘어가고 새 사용자 메시지 없이 다음 turn 이 바로 열린다")
    void returnedTextCarriesOverWithoutNewQuestion() throws InterruptedException {
        stub().willReturn(result("completed", "창가로"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "창가로", true);
        ChatMessage bubble = bubbles().getFirst();

        finish(running);
        awaitUntil("다음 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(messages.findById(bubble.id()).orElseThrow().steerState()).isEqualTo(SteerState.CARRIED_OVER);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).extracting(ChatMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT);
        assertThat(stub().received().get(1).input()).endsWith("창가로");
        AgentExecution next = executions.findAll().stream().max(Comparator.comparing(AgentExecution::id)).orElseThrow();
        assertThat(next.questionMessageId()).isEqualTo(bubble.id());
        assertThat(pendingRows.read(conversation.id()).isEmpty()).isTrue();
    }


    @Test
    @DisplayName("돌아온 글이 뒤쪽 글과만 맞으면 그 말풍선만 넘어가고 앞 말풍선은 전달됨으로 남는다")
    void onlyMatchingTailCarriesOver() throws InterruptedException {
        stub().willReturn(result("completed", "둘째"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "첫째", true);
        pending.enqueue(dad, conversation.id(), "둘째", true);

        finish(running);
        awaitUntil("다음 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(bubbles()).extracting(ChatMessage::steerState)
            .containsExactly(SteerState.DELIVERED, SteerState.CARRIED_OVER);
    }


    @Test
    @DisplayName("돌아온 글이 어느 묶음과도 맞지 않으면 그 turn 의 말풍선을 모두 다음 차례로 보낸다")
    void mismatchCarriesOverAll() throws InterruptedException {
        stub().willReturn(result("completed", "모르는 글"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "첫째", true);
        pending.enqueue(dad, conversation.id(), "둘째", true);

        finish(running);
        awaitUntil("다음 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(bubbles()).extracting(ChatMessage::steerState)
            .containsExactly(SteerState.CARRIED_OVER, SteerState.CARRIED_OVER);
        assertThat(stub().received().get(1).input()).endsWith("첫째\n\n둘째");
    }


    @Test
    @DisplayName("돌아온 글 없이 끝나면 전달됨으로 남고 다음 turn 을 열지 않는다")
    void deliveredStaysWhenNothingReturned() throws InterruptedException {
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "반말로", true);

        finish(running);

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.DELIVERED);
        assertThat(stub().received()).hasSize(1);
    }


    @Test
    @DisplayName("중지하면 확인 못 함으로 바뀌어 멈춘 대기 줄에 들어가고 보내기를 누르면 새 메시지 없이 다음 차례로 간다")
    void stopHoldsAndReleaseCarriesOver() throws InterruptedException {
        stub().willReturn(result("cancelled", null));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "그거 말고 위 것", true);
        ChatMessage bubble = bubbles().getFirst();
        chat.stop(dad, handle().getExecutionId());

        finish(running);

        assertThat(messages.findById(bubble.id()).orElseThrow().steerState()).isEqualTo(SteerState.UNCONFIRMED);
        PendingMessages queue = pendingRows.read(conversation.id());
        assertThat(queue.held()).isTrue();
        assertThat(queue.items()).extracting(PendingMessage::messageId).containsExactly(bubble.id());
        assertThat(stub().received()).hasSize(1);

        stub().willReturn(result("completed", null));
        pending.release(dad, conversation.id());
        awaitUntil("보낸 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(messages.findById(bubble.id()).orElseThrow().steerState()).isEqualTo(SteerState.CARRIED_OVER);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
            .filteredOn(message -> message.role() == MessageRole.USER).hasSize(2);
    }


    @Test
    @DisplayName("중지 뒤 되돌린 글을 취소하면 글만 빠지고 말풍선은 확인 못 함으로 남는다")
    void cancelAfterStopKeepsUnconfirmedBubble() throws InterruptedException {
        stub().willReturn(result("cancelled", null));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "취소할 글", true);
        chat.stop(dad, handle().getExecutionId());
        finish(running);

        pending.cancel(dad, conversation.id(), pendingRows.read(conversation.id()).items().getFirst().id());

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).isEmpty()).isTrue();
    }


    @Test
    @DisplayName("중지했는데 돌려준 글과 맞는 말풍선은 전달 안 됨으로, 앞 말풍선은 확인 못 함으로 바뀌어 모두 멈춘 대기 줄에 들어간다")
    void stopWithReturnedTextMarksNotDelivered() throws InterruptedException {
        stub().willReturn(result("cancelled", "둘째"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "첫째", true);
        pending.enqueue(dad, conversation.id(), "둘째", true);
        chat.stop(dad, handle().getExecutionId());

        finish(running);

        assertThat(bubbles()).extracting(ChatMessage::steerState)
            .containsExactly(SteerState.UNCONFIRMED, SteerState.NOT_DELIVERED);
        PendingMessages queue = pendingRows.read(conversation.id());
        assertThat(queue.held()).isTrue();
        assertThat(queue.items()).extracting(PendingMessage::messageId)
            .containsExactlyElementsOf(bubbles().stream().map(ChatMessage::id).toList());
        assertThat(stub().received()).hasSize(1);
    }


    @Test
    @DisplayName("중지했는데 돌려준 글이 어느 묶음과도 맞지 않으면 모두 확인 못 함으로 둔다")
    void stopWithMismatchedReturnStaysUnconfirmed() throws InterruptedException {
        stub().willReturn(result("cancelled", "모르는 글"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "첫째", true);
        chat.stop(dad, handle().getExecutionId());

        finish(running);

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).held()).isTrue();
    }


    @Test
    @DisplayName("전달 안 됨 글을 보내면 다음 차례로 넘어가고, 취소하면 말풍선은 전달 안 됨으로 남는다")
    void notDeliveredReleaseCarriesOverAndCancelKeeps() throws InterruptedException {
        stub().willReturn(result("cancelled", "보낼 글\n취소할 글"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "보낼 글", true);
        pending.enqueue(dad, conversation.id(), "취소할 글", true);
        chat.stop(dad, handle().getExecutionId());
        finish(running);
        List<ChatMessage> stopped = bubbles();

        pending.cancel(dad, conversation.id(), pendingRows.read(conversation.id()).items().get(1).id());
        stub().willReturn(result("completed", null));
        pending.release(dad, conversation.id());
        awaitUntil("보낸 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(messages.findById(stopped.get(0).id()).orElseThrow().steerState()).isEqualTo(SteerState.CARRIED_OVER);
        assertThat(messages.findById(stopped.get(1).id()).orElseThrow().steerState()).isEqualTo(SteerState.NOT_DELIVERED);
        assertThat(stub().received().get(1).input()).endsWith("보낼 글");
    }


    @Test
    @DisplayName("대화 turn 은 보내기 전에 같은 profile 에서 연결 실패로 끝난 지난 실행을 잃은 실행 풀기로 넘긴다")
    void chatTurnReleasesLostRunsBeforeSubmit() throws InterruptedException {
        Agent chief = agents.findByCode("dad").orElseThrow();
        AgentExecution lost = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .agentId(chief.id())
            .profileName(chief.hermesProfile())
            .hermesRunId("run-lost")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.FAILED)
            .errorCode("HERMES_UNAVAILABLE")
            .startedAt(Instant.now().minusSeconds(60))
            .build());
        jdbc.update("UPDATE agent_execution SET finished_at = ? WHERE id = ?", Timestamp.from(Instant.now()), lost.id());

        Thread running = startRunningTurn();
        finish(running);

        assertThat(stub().releasedRuns()).contains("run-lost");
    }


    @Test
    @DisplayName("넘어간 글이 있었는지 모르는 결과로 끝나면 중지한 turn 이어도 확인 못 함으로 멈춘 대기 줄에 둔다")
    void unknownPendingSteerHoldsEvenWhenStopped() throws InterruptedException {
        stub().willReturn(unknownPending("cancelled"));
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "끝 사건을 못 받은 글", true);
        chat.stop(dad, handle().getExecutionId());

        finish(running);

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).held()).isTrue();
    }


    @Test
    @DisplayName("기동 정리가 받은 결과가 넘어간 글을 모르면 그 말풍선을 확인 못 함으로 둔다")
    void recoveredUnknownPendingHolds() throws InterruptedException {
        Thread running = startRunningTurn();
        Long executionId = handle().getExecutionId();
        finish(running);
        ChatMessage left = messages.save(ChatMessage.fromSteer(
            conversation.id(), dad.id(), "모르는 글", executionId, SteerState.DELIVERED, null, Instant.now()));
        jdbc.update("UPDATE agent_execution SET status = 'RUNNING', finished_at = NULL WHERE id = ?", executionId);

        recorder.settle(executionId, unknownPending("interrupted"));

        assertThat(messages.findById(left.id()).orElseThrow().steerState()).isEqualTo(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).held()).isTrue();
    }


    @Test
    @DisplayName("결과를 읽지 못하고 끝나면 들어갔는지 모르는 것으로 보고 멈춘 대기 줄에 둔다")
    void unreadResultHolds() throws InterruptedException {
        CountDownLatch gate = release;
        stub().beforeAwait(() -> {
            await(gate);
            throw new ApiException(ErrorCode.HERMES_RUN_TIMEOUT, "the agent run did not finish in time");
        });
        Thread running = startTurnThread();
        pending.enqueue(dad, conversation.id(), "모르는 끝", true);

        finish(running);

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).held()).isTrue();
    }


    @Test
    @DisplayName("끼우기 답을 기다리는 동안 turn 이 끝나도 받은 말풍선이 그 turn 의 정리에 들어간다")
    void steerAcceptedWhileTurnEndsIsSettled() throws InterruptedException {
        stub().willReturn(result("completed", "늦은 글"));
        Thread running = startRunningTurn();
        stub().beforeSteer(() -> {
            release.countDown();
            sleep(300);
        });

        pending.enqueue(dad, conversation.id(), "늦은 글", true);
        running.join(WAIT_LIMIT.toMillis());
        awaitUntil("다음 turn", () -> stub().received().size() == 2);
        awaitIdle();

        assertThat(bubbles()).extracting(ChatMessage::steerState).containsExactly(SteerState.CARRIED_OVER);
    }


    @Test
    @DisplayName("기동 정리는 남은 끼우는 중 글을 확인 못 함으로 바꿔 멈춘 대기 줄에 두고 끝난 turn 의 전달됨 말풍선은 그대로 둔다")
    void startupResolvesStaleSteeringOnly() throws InterruptedException {
        Thread running = startRunningTurn();
        Long executionId = handle().getExecutionId();
        finish(running);
        ChatMessage delivered = messages.save(ChatMessage.fromSteer(
            conversation.id(), dad.id(), "따른 지시", executionId, SteerState.DELIVERED, null, Instant.now()));
        pendingRows.change(conversation.id(), queue -> {
            PendingMessages appended = queue.append("남은 끼우는 중", Instant.now());
            return appended.withSteering(appended.items().getLast().id(), true);
        });

        ledger.onStartup();

        assertThat(messages.findById(delivered.id()).orElseThrow().steerState())
            .as("완료로 정리한 말풍선을 되돌리면 따른 지시가 다시 실행될 수 있다")
            .isEqualTo(SteerState.DELIVERED);
        assertThat(bubbles()).filteredOn(message -> message.content().equals("남은 끼우는 중"))
            .extracting(ChatMessage::steerState).containsExactly(SteerState.UNCONFIRMED);
        PendingMessages queue = pendingRows.read(conversation.id());
        assertThat(queue.held()).isTrue();
        assertThat(queue.blockedBySteering()).isFalse();
        assertThat(queue.items()).extracting(PendingMessage::content).containsExactly("남은 끼우는 중");
    }


    @Test
    @DisplayName("기동 정리가 받은 끝난 결과에 돌아온 글이 있으면 그 말풍선을 다음 차례로 보낸다")
    void recoveredResultCarriesOver() throws InterruptedException {
        Thread running = startRunningTurn();
        Long executionId = handle().getExecutionId();
        finish(running);
        ChatMessage left = messages.save(ChatMessage.fromSteer(
            conversation.id(), dad.id(), "돌아올 글", executionId, SteerState.DELIVERED, null, Instant.now()));
        // 서버가 그 turn 이 도는 중에 멈춰 실행 줄이 RUNNING 으로 남은 것처럼 만든다.
        jdbc.update("UPDATE agent_execution SET status = 'RUNNING', finished_at = NULL WHERE id = ?", executionId);

        recorder.settle(executionId, result("completed", "돌아올 글"));

        assertThat(messages.findById(left.id()).orElseThrow().steerState()).isEqualTo(SteerState.CARRIED_OVER);
        assertThat(pendingRows.read(conversation.id()).carriedMessageIds()).containsExactly(left.id());
    }


    @Test
    @DisplayName("정리하다 실패한 대화는 다음 turn 을 정할 때 다시 정리한다")
    void failedSettleIsRetriedOnNextTryNext() throws InterruptedException {
        Thread running = startRunningTurn();
        Long executionId = handle().getExecutionId();
        finish(running);
        ChatMessage left = messages.save(ChatMessage.fromSteer(
            conversation.id(), dad.id(), "다시 정리할 글", executionId, SteerState.DELIVERED, null, Instant.now()));
        ChatMessage settledEarlier = messages.save(ChatMessage.fromSteer(
            conversation.id(), dad.id(), "앞서 완료로 정리한 글", executionId + 1000, SteerState.DELIVERED, null, Instant.now()));
        jdbc.update("UPDATE conversation SET pending_messages = ? WHERE id = ?", "{\"version\":9}", conversation.id());

        ledger.settle(executionId, conversation.id(), SteerSettlement.stopped(null));
        assertThat(messages.findById(left.id()).orElseThrow().steerState()).isEqualTo(SteerState.DELIVERED);

        jdbc.update("UPDATE conversation SET pending_messages = NULL WHERE id = ?", conversation.id());
        dispatcher.tryNext(conversation.id());

        assertThat(messages.findById(left.id()).orElseThrow().steerState()).isEqualTo(SteerState.UNCONFIRMED);
        assertThat(pendingRows.read(conversation.id()).held()).isTrue();
        assertThat(messages.findById(settledEarlier.id()).orElseThrow().steerState())
            .as("실패한 실행만 다시 정리한다").isEqualTo(SteerState.DELIVERED);
    }


    @Test
    @DisplayName("steer 말풍선이 낀 답은 다시 생성하지 않는다")
    void regenerateRejectsSteeredAnswer() throws InterruptedException {
        Thread running = startRunningTurn();
        pending.enqueue(dad, conversation.id(), "말투 반말로", true);
        finish(running);

        assertThatThrownBy(() -> chat.regenerate(dad, conversation.id(), event -> {
        }))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.MESSAGE_NOT_LATEST);
    }


    /** 완료 기다림에서 붙잡힌 turn 을 다른 스레드에서 돌린다. 루트 run 이 적힌 뒤 돌아온다. */
    private Thread startRunningTurn() {
        CountDownLatch gate = release;
        stub().beforeAwait(() -> await(gate));
        return startTurnThread();
    }


    private Thread startTurnThread() {
        Thread running = Thread.ofVirtual().start(() -> {
            try {
                chat.stream(dad, conversation.id(), "질문", null, event -> {
                });
            } catch (ApiException ignored) {
                // 단언은 이력과 대기 줄로 한다.
            }
        });
        awaitUntil("도는 turn 의 루트 run", () -> turns.findByConversation(conversation.id())
            .map(TurnHandle::getRootRun).isPresent());
        return running;
    }


    private void finish(Thread running) throws InterruptedException {
        release.countDown();
        running.join(WAIT_LIMIT.toMillis());
        awaitIdle();
    }


    private TurnHandle handle() {
        return turns.findByConversation(conversation.id()).orElseThrow();
    }


    private List<ChatMessage> bubbles() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
            .filter(message -> message.steerState() != null)
            .toList();
    }


    private static void await(CountDownLatch gate) {
        try {
            gate.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private CurrentUser signedUp(String email, String name) {
        AppUser user = users.save(AppUser.of(email, name, 1L, UserRole.MEMBER));
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
            ownerId);
    }


    /** Hermes v0.23.2 에서 끝 사건을 얻지 못한 결과다. 넘어간 글이 있었는지 모른다. */
    private static HermesRunResult unknownPending(String status) {
        return new HermesRunResult(
            "run", "session", status, "답", "model", "provider", null, TokenUsage.empty(), null, null, false);
    }


    private static HermesRunResult result(String status, String pendingSteer) {
        return new HermesRunResult(
            "run", "session", status, "답", "model", "provider", null, TokenUsage.empty(), null, pendingSteer);
    }


    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitUntil(
            "대화 " + it.id() + " 의 turn 종료", () -> !turns.markOf(it.id()).running()));
    }


    private void awaitIdle() {
        awaitUntil("turn 종료", () -> !turns.markOf(conversation.id()).running());
    }


    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("%s 을(를) %s 안에 보지 못했다", what, WAIT_LIMIT);
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
