package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.NextTurnDispatcher;
import com.bifos.assistant.chat.application.PendingMessageService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnHandle;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
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
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;

/**
 * 보낼 대기 메시지와 끝난 위임 결과가 함께 있을 때 사용자의 말이 먼저 가는지 본다(ADR-048).
 *
 * <p>두 turn 이 모두 테스트 스레드 밖에서 돈다. 앞 turn 이 닫히고 다음 turn 이 열리는 사이에는 잠금이 잠깐 비므로,
 * 잠금이 아니라 쌓인 메시지 수를 기다린 뒤 단언한다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class PendingBeforeDelegationTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    /** 대기 메시지 turn 이 사용자 메시지를 저장하기 전에 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ChatService chat;

    /** 대기 행을 멈추다 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ChatPendingMessageRepository pendingRows;

    @Autowired
    PendingMessageService pending;

    @Autowired
    NextTurnDispatcher dispatcher;

    /** 대기 줄이 멈췄다는 알림이 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ConversationEventHub hub;

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
    private Agent worker;
    private Conversation conversation;
    private AgentExecution root;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        // 이 문맥이 뜰 때 기동 확인이 앞 검사들이 남긴 것으로 연 turn 이 있을 수 있다. 지우기 전에 끝나기를 기다린다.
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
                "run", "session", "completed", "답", "model", "provider", TokenUsage.empty()));
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
        pendingRows.deleteAll();
    }

    @Test
    @DisplayName("대기 메시지와 끝난 위임 결과가 함께 있으면 대기 메시지를 먼저 보내고 그 뒤에 결과를 전한다")
    void sendsQueuedMessageBeforeDeliveringDelegationResult() {
        TurnHandle running = turns.open(dad.id(), conversation.id());
        AgentExecution done = delegated();
        finished(done);
        pending.enqueue(dad, conversation.id(), "대기 글");

        turns.close(running);
        awaitMessages(4);
        awaitIdle(conversation.id());

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(history.getFirst().content()).isEqualTo("대기 글");
        assertThat(history.get(2).content()).isEqualTo("조사원 에이전트의 결과가 도착했어요");
        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().getFirst().input()).endsWith("대기 글");
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .isEmpty();
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("전했다는 표시")
                .isNotNull();
    }

    @Test
    @DisplayName("대기 줄이 멈춰 있으면 위임 결과의 자동 turn 은 열리고 대기 행은 멈춘 채로 남는다")
    void opensAutoTurnAndKeepsHeldQueueWhenQueueIsHeld() {
        pendingRows.save(ChatPendingMessage.queued(
                conversation.id(), dad.id(), "멈춘 글", true, Instant.parse("2026-09-30T00:00:00Z")));
        AgentExecution done = delegated();

        finished(done);
        awaitMessages(2);
        awaitIdle(conversation.id());

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(stub().received()).as("자동 turn 하나만 Hermes 에 갔다").hasSize(1);
        assertThat(stub().received().getFirst().input()).doesNotContain("멈춘 글");
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatPendingMessage::held)
                .containsExactly(true);
    }

    @Test
    @DisplayName("중지로 닫힌 turn 뒤에 대기 줄 알림이 실패해도 위임 결과의 자동 turn 은 열린다")
    void opensAutoTurnEvenWhenHeldNoticeFailsAfterStoppedTurn() {
        // 자동 turn 의 사건은 그대로 나가고, 대기 줄이 멈췄다는 알림만 실패한다.
        doThrow(new IllegalStateException("알림을 보내지 못했다"))
                .when(hub)
                .publish(eq(conversation.id()), argThat(event -> event != null && "pending".equals(event.type())));
        pendingRows.save(ChatPendingMessage.queued(
                conversation.id(), dad.id(), "멈춘 글", true, Instant.parse("2026-09-30T00:00:00Z")));
        AgentExecution done = delegated();
        TurnHandle running = turns.open(dad.id(), conversation.id());
        finished(done);
        turns.markStopped(running);

        turns.close(running);
        awaitMessages(2);
        awaitIdle(conversation.id());

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .as("전했다는 표시")
                .isNotNull();
    }

    @Test
    @DisplayName("보내려다 실패하고 대기 행을 멈추지도 못하면 한동안 다시 열지 않고 위임 결과는 전한다")
    void backsOffQueuedTurnWhenHoldFailsAndStillDeliversDelegationResult() {
        doThrow(new IllegalStateException("사용자 메시지를 저장하기 전에 실패"))
                .when(chat)
                .runPendingMessages(any(), any(), any(), any());
        doThrow(new IllegalStateException("대기 행을 멈추지 못했다")).when(pendingRows).markHeld(any(), eq(true));
        TurnHandle running = turns.open(dad.id(), conversation.id());
        AgentExecution done = delegated();
        finished(done);
        pending.enqueue(dad, conversation.id(), "대기 글");
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        try {
            turns.close(running);
            awaitMessages(2);
            awaitIdle(conversation.id());
            dispatcher.tryNext(conversation.id());
            awaitIdle(conversation.id());
        } finally {
            unsubscribe.run();
        }

        assertThat(received.stream().filter(event -> "error".equals(event.type())))
                .as("닫을 때와 뒤이은 호출이 다시 열지 않아 실패 알림은 하나다")
                .singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo("INTERNAL_ERROR"));
        assertThat(pendingRows.findByConversationIdOrderByIdAsc(conversation.id()))
                .as("멈추지 못한 대기 행")
                .extracting(ChatPendingMessage::held)
                .containsExactly(false);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .as("그동안에도 위임 결과의 자동 turn 은 열린다")
                .containsExactly(MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(executions.findById(done.id()).orElseThrow().resultDeliveredAt())
                .isNotNull();
    }

    @Test
    @DisplayName("중지한 turn 의 대기 줄을 멈추지 못해도 실행은 CANCELLED 로 남고 stopped 사건이 온다")
    void recordsCancelledAndSendsStoppedEvenWhenHoldFails() {
        doThrow(new IllegalStateException("대기 행을 멈추지 못했다")).when(pendingRows).markHeld(any(), eq(true));
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "절반", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(
                dad,
                executions
                        .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 1))
                        .getFirst()
                        .id()));
        List<ChatEvent> relayed = new CopyOnWriteArrayList<>();

        chat.stream(dad, conversation.id(), "질문", null, relayed::add);
        awaitIdle(conversation.id());

        ChatEvent last = relayed.getLast();
        assertThat(last.type()).as("받은 사건: %s", relayed).isEqualTo("stopped");
        assertThat(executions.findById(last.executionId()).orElseThrow().status())
                .as("중지한 turn 의 실행 줄")
                .isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .as("멈추기 전까지의 답이 남는다")
                .containsExactly("질문", "절반");
        assertLockReleased();
    }

    @Test
    @DisplayName("흐름 turn 을 중지할 때 대기 줄을 멈추지 못해도 stopped 로 끝나고 잠금이 풀린다")
    void flowTurnEndsStoppedAndReleasesLockEvenWhenHoldFails() {
        doThrow(new IllegalStateException("대기 행을 멈추지 못했다")).when(pendingRows).markHeld(any(), eq(true));
        Agent chief = agents.findById(conversation.agentId()).orElseThrow();
        chief.assignFlow(ResearchAndBuildFlow.NAME);
        agents.save(chief);
        stub().beforeAwait(() -> chat.stop(
                dad,
                executions
                        .findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 1))
                        .getFirst()
                        .id()));
        List<ChatEvent> relayed = new CopyOnWriteArrayList<>();

        chat.stream(dad, conversation.id(), "질문", null, relayed::add);
        awaitIdle(conversation.id());

        ChatEvent last = relayed.getLast();
        assertThat(last.type()).as("받은 사건: %s", relayed).isEqualTo("stopped");
        assertThat(executions.findById(last.executionId()).orElseThrow().status())
                .as("중지한 흐름 turn 의 루트 실행 줄")
                .isEqualTo(ExecutionStatus.CANCELLED);
        assertLockReleased();
    }

    /** 그 대화에 도는 turn 이 없고 다음 turn 을 열 수 있다. */
    private void assertLockReleased() {
        assertThat(turns.markOf(conversation.id()).running())
                .as("중지한 turn 의 잠금")
                .isFalse();
        TurnHandle next = turns.open(dad.id(), conversation.id());
        turns.close(next);
    }

    private void finished(AgentExecution execution) {
        publisher.publishEvent(new DelegationFinished(conversation.id(), execution.id()));
    }

    /** 대화 turn 이 직접 맡긴, 성공으로 끝난 위임 실행 줄을 만든다. */
    private AgentExecution delegated() {
        AgentExecution execution = AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
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

    private void awaitMessages(int count) {
        awaitUntil(
                "메시지 " + count + "개",
                () -> messages.findByConversationIdOrderByIdAsc(conversation.id())
                                .size()
                        >= count);
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
