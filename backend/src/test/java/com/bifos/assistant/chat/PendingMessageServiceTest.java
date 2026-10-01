package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.NextTurnDispatcher;
import com.bifos.assistant.chat.application.PendingMessageService;
import com.bifos.assistant.chat.application.PendingQueue;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * turn 이 도는 동안 보낸 글이 대기 줄에 쌓였다가 다음 turn 으로 합쳐 가는지 본다(ADR-047).
 *
 * <p>대기 메시지로 연 turn 은 테스트 스레드 밖의 가상 스레드에서 돈다. 검사마다 그 turn 이 끝날 때까지 기다린 뒤
 * 단언한다. 남은 대기 행은 다른 검사 문맥의 기동 확인이 turn 으로 보내므로 앞뒤에서 비운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class PendingMessageServiceTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final Instant QUEUED_AT = Instant.parse("2026-09-30T00:00:00Z");

    /** 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    @Autowired
    PendingMessageService pending;

    @Autowired
    ChatService chat;

    @Autowired
    NextTurnDispatcher dispatcher;

    @Autowired
    ConversationEventHub hub;

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
    ChatPendingMessageRepository pendingRows;

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
    private Agent chief;
    private Conversation conversation;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        // 이 문맥이 뜰 때 기동 확인이 앞 검사들이 남긴 대기 행으로 연 turn 이 있을 수 있다. 지우기 전에 끝나기를 기다린다.
        awaitAllIdle();
        stub().reset();
        pendingRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        dad = signedUp("dad@example.com", "dad");
        chief = agents.save(agent("dad", "비서", dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id()));
        stub().willReturn(result("run", "답"));
    }

    @AfterEach
    void tearDown() {
        stub().releaseSubmits();
        awaitAllIdle();
        pendingRows.deleteAll();
    }

    @Test
    @DisplayName("turn 이 도는 동안 더한 두 글은 그 turn 이 끝난 뒤 합쳐져 사용자 메시지 하나로 간다")
    void sendsTwoQueuedMessagesMergedAfterRunningTurnEnds() throws InterruptedException {
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        try {
            Thread running = startHeldTurn("질문");
            pending.enqueue(dad, conversation.id(), "첫째 글");
            PendingQueue queued = pending.enqueue(dad, conversation.id(), "둘째 글");
            assertThat(queued.held()).isFalse();
            assertThat(queued.items()).extracting(ChatPendingMessage::content).containsExactly("첫째 글", "둘째 글");
            assertThat(stub().received()).as("앞 turn 이 도는 동안 Hermes 에 보낸 것").hasSize(1);

            stub().releaseSubmits();
            running.join(WAIT_LIMIT.toMillis());
            awaitIdle(conversation.id());
        } finally {
            unsubscribe.run();
        }

        String merged = "첫째 글\n\n둘째 글";
        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().get(1).input()).endsWith(merged);
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(history.get(2).content()).isEqualTo(merged);
        assertThat(rowsOf(conversation)).as("보낸 뒤 남은 대기 행").isEmpty();
        assertThat(conversations.findById(conversation.id()).orElseThrow().autoTurnCount())
                .isZero();

        assertThat(received)
                .extracting(ChatEvent::type)
                .as("대화 단위로 받은 사건")
                .containsSubsequence("pending", "user", "started", "done");
        ChatEvent user = received.stream()
                .filter(event -> "user".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(user.text()).isEqualTo(merged);
        assertThat(user.messageId()).isEqualTo(history.get(2).id());
        assertThat(user.conversationId()).isEqualTo(conversation.publicId());
    }

    @Test
    @DisplayName("도는 turn 이 없을 때 더하면 곧바로 turn 이 열려 그 글이 간다")
    void sendsImmediatelyWhenNoTurnIsRunning() {
        pending.enqueue(dad, conversation.id(), "바로 갈 글");
        awaitIdle(conversation.id());

        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).endsWith("바로 갈 글");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(rowsOf(conversation)).isEmpty();
    }

    @Test
    @DisplayName("취소한 대기 메시지는 가지 않고 같은 번호를 다시 취소하면 없는 것으로 답한다")
    void sendsOnlyRemainingMessageAfterCancelAndRejectsSecondCancel() {
        TurnCancellation.TurnHandle running = turns.open(dad.id(), conversation.id());
        Long cancelledId;
        try {
            PendingQueue queued = pending.enqueue(dad, conversation.id(), "취소할 글");
            pending.enqueue(dad, conversation.id(), "남길 글");
            cancelledId = queued.items().getFirst().id();

            pending.cancel(dad, conversation.id(), cancelledId);
        } finally {
            turns.close(running);
        }
        awaitIdle(conversation.id());

        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).endsWith("남길 글").doesNotContain("취소할 글");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())
                        .getFirst()
                        .content())
                .isEqualTo("남길 글");
        assertThatCode(() -> pending.cancel(dad, conversation.id(), cancelledId), ErrorCode.PENDING_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("대기 메시지가 다섯이면 여섯째를 받지 않는다")
    void rejectsSixthMessage() {
        TurnCancellation.TurnHandle running = turns.open(dad.id(), conversation.id());
        try {
            for (int index = 1; index <= 5; index++) {
                pending.enqueue(dad, conversation.id(), "글 " + index);
            }

            assertThatCode(() -> pending.enqueue(dad, conversation.id(), "글 6"), ErrorCode.PENDING_QUEUE_FULL);

            assertThat(rowsOf(conversation)).hasSize(5);
        } finally {
            pendingRows.deleteAll();
            turns.close(running);
        }
    }

    @Test
    @DisplayName("합친 길이가 8000자를 넘으면 받지 않고 꼭 8000자면 받는다")
    void rejectsWhenMergedLengthExceedsLimitAndAcceptsAtLimit() {
        TurnCancellation.TurnHandle running = turns.open(dad.id(), conversation.id());
        try {
            pending.enqueue(dad, conversation.id(), "가".repeat(4000));

            // 사이에 넣는 빈 줄이 두 글자라 4000 + 2 + 3999 = 8001 이다.
            assertThatCode(
                    () -> pending.enqueue(dad, conversation.id(), "나".repeat(3999)), ErrorCode.PENDING_QUEUE_FULL);
            assertThat(rowsOf(conversation)).hasSize(1);

            PendingQueue atLimit = pending.enqueue(dad, conversation.id(), "다".repeat(3998));
            assertThat(atLimit.items()).hasSize(2);
        } finally {
            pendingRows.deleteAll();
            turns.close(running);
        }
    }

    @Test
    @DisplayName("도는 turn 을 중지하면 대기 줄을 멈춰 두고 풀면 합친 글이 간다")
    void holdsQueueWhenTurnStoppedAndSendsAfterRelease() {
        stub().willReturnInOrder(cancelled("run-stop"), result("run-next", "다음 답"));
        enqueueAndStopBeforeTurnCompletes("기다린 글");
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        AtomicReference<Long> pendingEventsBeforeClose = new AtomicReference<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        try {
            chat.stream(dad, conversation.id(), "질문", null, event -> {
                if ("stopped".equals(event.type())) {
                    // stopped 는 잠금을 풀기 전에 온다. 이때까지 받은 것은 더할 때 나온 사건이다.
                    pendingEventsBeforeClose.set(pendingEventsIn(received));
                }
            });
        } finally {
            unsubscribe.run();
        }
        awaitIdle(conversation.id());

        assertThat(rowsOf(conversation))
                .as("중지한 turn 뒤의 대기 행")
                .extracting(ChatPendingMessage::held)
                .containsExactly(true);
        assertThat(pending.queue(dad, conversation.id()).held()).isTrue();
        assertThat(stub().received()).as("중지 뒤에 새 turn 이 열리지 않았다").hasSize(1);
        assertThat(pendingEventsBeforeClose.get())
                .as("turn 이 닫히기 전에 받은 pending 사건")
                .isEqualTo(1L);
        assertThat(pendingEventsIn(received))
                .as("중지로 닫힐 때 대기 줄이 멈췄다는 pending 사건이 하나 더 온다")
                .isEqualTo(2L);

        PendingQueue released = pending.release(dad, conversation.id());
        awaitIdle(conversation.id());

        assertThat(released.held()).isFalse();
        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().get(1).input()).endsWith("기다린 글");
        assertThat(rowsOf(conversation)).isEmpty();
    }

    @Test
    @DisplayName("중지한 turn 의 잠금이 풀리기 전에 대기 줄이 이미 멈춰 있어 그때 다음 turn 을 정해도 열리지 않는다")
    void holdsQueueBeforeLockReleasedSoEarlyDispatchOpensNothing() {
        stub().willReturnInOrder(cancelled("run-stop"), result("run-next", "다음 답"));
        enqueueAndStopBeforeTurnCompletes("기다린 글");
        AtomicReference<Boolean> lockHeldAtStop = new AtomicReference<>();
        AtomicReference<List<Boolean>> heldAtStop = new AtomicReference<>();

        chat.stream(dad, conversation.id(), "질문", null, event -> {
            if ("stopped".equals(event.type())) {
                // stopped 는 잠금을 풀기 전에 온다. 이때 읽은 값이 잠금 안에서 멈췄는지를 말한다.
                lockHeldAtStop.set(turns.markOf(conversation.id()).running());
                heldAtStop.set(rowsOf(conversation).stream()
                        .map(ChatPendingMessage::held)
                        .toList());
                dispatcher.tryNext(conversation.id());
            }
        });
        awaitIdle(conversation.id());

        assertThat(lockHeldAtStop.get()).as("stopped 를 받은 때 turn 잠금").isTrue();
        assertThat(heldAtStop.get()).as("잠금이 풀리기 전에 읽은 대기 행의 held").containsExactly(true);
        assertThat(stub().received()).as("Hermes 에 보낸 것은 중지한 turn 하나다").hasSize(1);
        assertThat(rowsOf(conversation)).extracting(ChatPendingMessage::held).containsExactly(true);
    }

    @Test
    @DisplayName("중지가 확정된 turn 이 예외로 끝나도 잠금을 풀기 전에 대기 줄을 멈춰 둔다")
    void holdsQueueWhenStopConfirmedTurnEndsWithException() {
        AtomicBoolean done = new AtomicBoolean();
        stub().beforeAwait(() -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            pending.enqueue(dad, conversation.id(), "기다린 글");
            chat.stop(dad, latestExecutionId());
            stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "agent runtime is down"));
        });

        assertThatCode(
                () -> chat.stream(dad, conversation.id(), "질문", null, event -> {}), ErrorCode.HERMES_UNAVAILABLE);
        awaitIdle(conversation.id());

        assertThat(rowsOf(conversation))
                .as("중지한 turn 이 예외로 끝난 뒤의 대기 행")
                .extracting(ChatPendingMessage::held)
                .containsExactly(true);
        assertThat(stub().received()).as("Hermes 에 보낸 것은 중지한 turn 하나다").hasSize(1);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .as("대기 글은 사용자 메시지가 되지 않았다")
                .containsExactly("질문");
    }

    @Test
    @DisplayName("멈춘 대기 줄에 더한 글도 멈춘 채로 들어가고 turn 이 열리지 않는다")
    void addsHeldRowToHeldQueueWithoutOpeningTurn() {
        pendingRows.save(ChatPendingMessage.queued(conversation.id(), dad.id(), "멈춘 글", true, QUEUED_AT));

        PendingQueue queued = pending.enqueue(dad, conversation.id(), "새 글");
        awaitIdle(conversation.id());

        assertThat(queued.held()).isTrue();
        assertThat(rowsOf(conversation)).extracting(ChatPendingMessage::held).containsExactly(true, true);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("앞 turn 이 실패로 끝나도 대기 메시지는 간다")
    void sendsQueuedMessageAfterFailedTurn() throws InterruptedException {
        stub().willAnswer(command -> {
            if (command.input().endsWith("실패할 질문")) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "agent runtime is down");
            }
            return result("run-next", "다음 답");
        });
        AtomicReference<ErrorCode> failure = new AtomicReference<>();
        Thread running = startHeldTurn("실패할 질문", failure);
        pending.enqueue(dad, conversation.id(), "기다린 글");

        stub().releaseSubmits();
        running.join(WAIT_LIMIT.toMillis());
        awaitIdle(conversation.id());

        assertThat(failure.get()).as("앞 turn 의 실패").isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().get(1).input()).endsWith("기다린 글");
        assertThat(rowsOf(conversation)).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("더한 뒤 에이전트가 꺼지면 대기 줄을 멈춰 두고 오류를 알리며 turn 을 되풀이해 열지 않는다")
    void holdsQueueAndReportsErrorWhenAgentDisabledAfterEnqueue() {
        TurnCancellation.TurnHandle running = turns.open(dad.id(), conversation.id());
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        try {
            pending.enqueue(dad, conversation.id(), "기다린 글");
            chief.changeAccess(false, chief.visibility(), chief.ownerUserId());
            agents.save(chief);

            turns.close(running);
            awaitUntil("오류 사건", () -> received.stream().anyMatch(event -> "error".equals(event.type())));
            awaitIdle(conversation.id());
        } finally {
            unsubscribe.run();
        }

        assertThat(rowsOf(conversation)).extracting(ChatPendingMessage::held).containsExactly(true);
        assertThat(received.stream().filter(event -> "error".equals(event.type())))
                .as("같은 실패를 되풀이하지 않아 오류는 하나다")
                .singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo(ErrorCode.AGENT_DISABLED.name()));
        assertThat(received).extracting(ChatEvent::type).containsSubsequence("pending", "error", "pending");
        assertThat(stub().received()).as("Hermes 에 보낸 것").isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
    }

    @Test
    @DisplayName("흐름이 붙은 에이전트의 대화는 대기 메시지를 받지 않는다")
    void rejectsQueueingOnFlowAgentConversation() {
        Agent flowAgent = agent("flowed", "흐름", dad.id());
        flowAgent.assignFlow(ResearchAndBuildFlow.NAME);
        Conversation flowed = conversations.save(
                Conversation.startedBy(dad.id(), "흐름 대화", agents.save(flowAgent).id()));

        assertThatCode(() -> pending.enqueue(dad, flowed.id(), "글"), ErrorCode.CONVERSATION_BUSY);

        assertThat(rowsOf(flowed)).isEmpty();
    }

    @Test
    @DisplayName("남의 대화에는 더하지도 읽지도 못한다")
    void rejectsQueueingAndReadingOthersConversation() {
        CurrentUser mom = signedUp("mom@example.com", "mom");

        assertThatCode(() -> pending.enqueue(mom, conversation.id(), "글"), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThatCode(() -> pending.queue(mom, conversation.id()), ErrorCode.CONVERSATION_NOT_FOUND);

        assertThat(rowsOf(conversation)).isEmpty();
    }

    @Test
    @DisplayName("기동 확인은 멈추지 않은 대기 메시지를 보내고 멈춘 대기 줄은 그대로 둔다")
    void startupDispatchSendsReadyQueueAndLeavesHeldQueue() {
        Conversation heldConversation = conversations.save(Conversation.startedBy(dad.id(), "멈춘 대화", chief.id()));
        pendingRows.save(ChatPendingMessage.queued(conversation.id(), dad.id(), "기동 뒤에 갈 글", false, QUEUED_AT));
        pendingRows.save(ChatPendingMessage.queued(heldConversation.id(), dad.id(), "멈춘 글", true, QUEUED_AT));

        dispatcher.dispatchAfterStartup();
        awaitIdle(conversation.id());
        awaitIdle(heldConversation.id());

        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).endsWith("기동 뒤에 갈 글");
        assertThat(rowsOf(conversation)).isEmpty();
        assertThat(rowsOf(heldConversation))
                .extracting(ChatPendingMessage::content)
                .containsExactly("멈춘 글");
        assertThat(messages.findByConversationIdOrderByIdAsc(heldConversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("대화를 지우면 그 대화의 대기 행도 지운다")
    void deletesQueuedRowsWithConversation() {
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "다른 대화", chief.id()));
        pendingRows.save(ChatPendingMessage.queued(conversation.id(), dad.id(), "지워질 글", true, QUEUED_AT));
        pendingRows.save(ChatPendingMessage.queued(other.id(), dad.id(), "남을 글", true, QUEUED_AT));

        chat.delete(dad, conversation.id());

        assertThat(rowsOf(conversation)).isEmpty();
        assertThat(rowsOf(other)).hasSize(1);
    }

    private static void assertThatCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }

    private List<ChatPendingMessage> rowsOf(Conversation target) {
        return pendingRows.findByConversationIdOrderByIdAsc(target.id());
    }

    private Thread startHeldTurn(String text) {
        return startHeldTurn(text, new AtomicReference<>());
    }

    /**
     * 대역 Hermes 의 제출에서 붙잡힌 turn 을 다른 스레드에서 돌린다. 제출에 닿아 turn 잠금이 잡힌 뒤에 돌아온다.
     *
     * @param failure 그 turn 이 실패로 끝나면 오류 코드를 담는다
     */
    private Thread startHeldTurn(String text, AtomicReference<ErrorCode> failure) {
        stub().holdSubmits();
        Thread running = Thread.ofVirtual().start(() -> {
            try {
                chat.stream(dad, conversation.id(), text, null, event -> {});
            } catch (ApiException ex) {
                failure.set(ex.code());
            }
        });
        awaitUntil("앞 turn 의 제출", () -> !stub().received().isEmpty());
        return running;
    }

    /** 앞 turn 이 완료를 기다리기 직전에 대기 메시지를 더하고 그 turn 을 중지하게 한다. 한 번만 한다. */
    private void enqueueAndStopBeforeTurnCompletes(String text) {
        AtomicBoolean done = new AtomicBoolean();
        stub().beforeAwait(() -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            pending.enqueue(dad, conversation.id(), text);
            chat.stop(dad, latestExecutionId());
        });
    }

    private Long latestExecutionId() {
        return executions
                .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 1))
                .getFirst()
                .id();
    }

    private static long pendingEventsIn(List<ChatEvent> events) {
        return events.stream().filter(event -> "pending".equals(event.type())).count();
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

    private static HermesRunResult result(String runId, String output) {
        return HermesRunResult.of(runId, "session", "completed", output, "model", "provider", TokenUsage.empty());
    }

    private static HermesRunResult cancelled(String runId) {
        return HermesRunResult.of(runId, "session", "cancelled", "절반", "model", "provider", TokenUsage.empty());
    }

    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitIdle(it.id()));
    }

    /** 그 대화에 도는 turn 이 없어질 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private void awaitIdle(Long conversationId) {
        awaitUntil(
                "대화 " + conversationId + " 의 turn 종료",
                () -> !turns.markOf(conversationId).running());
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
