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
import com.bifos.assistant.chat.conversation.domain.type.SteerChild;
import com.bifos.assistant.chat.conversation.domain.type.SteerState;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.NextTurnDispatcher;
import com.bifos.assistant.chat.nextturn.application.PendingMessageService;
import com.bifos.assistant.chat.nextturn.application.SteerService;
import com.bifos.assistant.chat.nextturn.config.SteerProperties;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.TurnIntent;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.dto.SteerOutcome;
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
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 답이 도는 중 입력창 글을 도는 루트 실행에 끼워 넣는지 본다(ADR-20261116 / run-steer).
 *
 * <p>도는 turn 은 대역 Hermes 의 완료 기다림에서 붙잡는다. 제출은 끝나 루트 run 이 적힌 뒤다.
 */
@BackendIntegrationTest
class SteerServiceTest {

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
    private final List<ChatEvent> received = new CopyOnWriteArrayList<>();
    private Runnable unsubscribe = () -> {
    };


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
        stub().willReturn(result("run", "답"));
        release = new CountDownLatch(1);
        received.clear();
        unsubscribe = hub.subscribe(conversation.id(), received::add);
    }


    @AfterEach
    void tearDown() {
        unsubscribe.run();
        release.countDown();
        stub().beforeAwait(() -> {
        });
        awaitAllIdle();
        PendingQueueFixtures.clearAll(jdbc);
    }


    @Test
    @DisplayName("Hermes 가 받으면 대기 글이 빠지고 답 앞에 전달됨 말풍선이 남으며 대화 단위 사건이 나간다")
    void acceptedSteerLeavesDeliveredBubble() throws InterruptedException {
        Thread running = startRunningTurn("질문");
        Long executionId = handle().getExecutionId();

        pending.enqueue(dad, conversation.id(), "2만원 이하로", true);

        assertThat(stub().steered()).extracting(StubHermesRunsClient.SteerRequest::text).containsExactly("2만원 이하로");
        assertThat(stub().steered().getFirst().runId()).isEqualTo("run");
        assertThat(pendingRows.read(conversation.id()).isEmpty()).isTrue();
        ChatMessage bubble = steerBubbles().getFirst();
        assertThat(bubble.steerState()).isEqualTo(SteerState.DELIVERED);
        assertThat(bubble.steerExecutionId()).isEqualTo(executionId);
        assertThat(bubble.executionId()).as("사용자 줄의 execution_id 는 비어 있다").isNull();
        assertThat(bubble.content()).isEqualTo("2만원 이하로");
        ChatEvent event = received.stream().filter(it -> "steer".equals(it.type())).findFirst().orElseThrow();
        assertThat(event.messageId()).isEqualTo(bubble.id());
        assertThat(event.executionId()).isEqualTo(executionId);
        assertThat(event.steer().state()).isEqualTo("DELIVERED");

        finish(running);
        assertThat(stub().received()).as("받은 글로 다음 turn 을 열지 않는다").hasSize(1);
    }


    @Test
    @DisplayName("Hermes 가 409 로 받지 않으면 글은 대기 줄에 남고 turn 이 끝난 뒤 다음 turn 으로 간다")
    void rejectedSteerKeepsMessageQueued() throws InterruptedException {
        stub().willSteer(SteerOutcome.REJECTED);
        Thread running = startRunningTurn("질문");

        pending.enqueue(dad, conversation.id(), "창가로", true);

        PendingMessages queue = pendingRows.read(conversation.id());
        assertThat(queue.items()).extracting(PendingMessage::content).containsExactly("창가로");
        assertThat(queue.blockedBySteering()).isFalse();
        assertThat(steerBubbles()).isEmpty();

        finish(running);
        awaitUntil("다음 turn", () -> stub().received().size() == 2);
        awaitIdle();
        assertThat(stub().received().get(1).input()).endsWith("창가로");
    }


    @Test
    @DisplayName("받았는지 모르면 확인 못 함 말풍선을 남기고 같은 글을 말풍선 번호를 붙여 멈춘 대기 줄에 둔다")
    void unknownSteerHoldsCarriedMessage() throws InterruptedException {
        stub().willSteer(SteerOutcome.UNKNOWN);
        Thread running = startRunningTurn("질문");

        pending.enqueue(dad, conversation.id(), "그거 말고 위 것", true);

        ChatMessage bubble = steerBubbles().getFirst();
        assertThat(bubble.steerState()).isEqualTo(SteerState.UNCONFIRMED);
        PendingMessages queue = pendingRows.read(conversation.id());
        assertThat(queue.held()).isTrue();
        assertThat(queue.items()).extracting(PendingMessage::messageId).containsExactly(bubble.id());

        finish(running);
        assertThat(stub().received()).as("멈춘 글은 사람이 고르기 전에 보내지 않는다").hasSize(1);
    }


    @Test
    @DisplayName("대기 줄에 글이 있으면 새 글은 끼우지 않고 뒤에 더한다")
    void doesNotSteerWhenQueueIsNotEmpty() throws InterruptedException {
        Thread running = startRunningTurn("질문");

        pending.enqueue(dad, conversation.id(), "앞 글", false);
        pending.enqueue(dad, conversation.id(), "뒤 글", true);

        assertThat(stub().steered()).isEmpty();
        assertThat(pendingRows.read(conversation.id()).items()).extracting(PendingMessage::content)
            .containsExactly("앞 글", "뒤 글");
        finish(running);
    }


    @Test
    @DisplayName("입력창 표시가 없거나 스킬 커맨드면 끼우지 않는다")
    void doesNotSteerWithoutRequestOrForSkillCommand() throws InterruptedException {
        Thread running = startRunningTurn("질문");

        pending.enqueue(dad, conversation.id(), "카드 답", false);
        assertThat(stub().steered()).isEmpty();
        pending.cancel(dad, conversation.id(), pendingRows.read(conversation.id()).items().getFirst().id());

        pending.enqueue(dad, conversation.id(), "/summary 이 대화", true);
        assertThat(stub().steered()).isEmpty();
        finish(running);
    }


    @Test
    @DisplayName("맡김 turn 이나 이미 정리한 turn 에는 끼우지 않는다")
    void doesNotSteerIntoMentionOrSettledTurn() throws InterruptedException {
        Thread running = startRunningTurn("질문");
        TurnHandle handle = handle();

        turns.markIntent(handle, new TurnIntent.Mention());
        pending.enqueue(dad, conversation.id(), "맡김 중 글", true);
        assertThat(stub().steered()).isEmpty();
        pending.cancel(dad, conversation.id(), pendingRows.read(conversation.id()).items().getFirst().id());

        turns.markIntent(handle, new TurnIntent.Fresh(List.of()));
        synchronized (handle.steerLock()) {
            handle.markSteerSettled();
        }
        pending.enqueue(dad, conversation.id(), "정리 뒤 글", true);
        assertThat(stub().steered()).isEmpty();
        finish(running);
    }


    @Test
    @DisplayName("하위 에이전트가 돌면 SUBAGENT, 맡긴 실행만 돌면 DELEGATION 을 말풍선에 적는다")
    void recordsChildKind() throws InterruptedException {
        Thread running = startRunningTurn("질문");
        TurnHandle handle = handle();

        turns.subagentStarted(handle);
        pending.enqueue(dad, conversation.id(), "첫 글", true);
        turns.subagentCompleted(handle);
        // 도는 루트 줄에 위임 키를 붙여 그 대화에 도는 맡긴 실행이 있는 것처럼 만든다.
        jdbc.update("UPDATE agent_execution SET delegation_key = 'k' WHERE id = ?", handle.getExecutionId());
        pending.enqueue(dad, conversation.id(), "둘째 글", true);

        assertThat(steerBubbles()).extracting(ChatMessage::steerChild)
            .containsExactly(SteerChild.SUBAGENT, SteerChild.DELEGATION);
        jdbc.update("UPDATE agent_execution SET delegation_key = NULL WHERE id = ?", handle.getExecutionId());
        finish(running);
    }


    @Test
    @DisplayName("끼우는 중인 글은 취소할 수 없고 그 줄은 다음 turn 을 붙잡는다")
    void steeringMessageCannotBeCancelledAndHoldsNextTurn() {
        pendingRows.change(conversation.id(), queue -> {
            PendingMessages appended = queue.append("끼우는 중", Instant.now());
            return appended.withSteering(appended.items().getLast().id(), true);
        });
        Long id = pendingRows.read(conversation.id()).items().getFirst().id();

        assertThatThrownBy(() -> pending.cancel(dad, conversation.id(), id))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.CONVERSATION_BUSY);
        dispatcher.tryNext(conversation.id());
        assertThat(stub().received()).as("끼우는 중인 줄로 연 turn").isEmpty();
    }


    @Test
    @DisplayName("설정이 꺼져 있으면 끼울 수 없다고 본다")
    void disabledSettingNeverSteers() {
        SteerService disabled = new SteerService(new SteerProperties(false), turns, hermes, null, null);

        assertThat(disabled.eligible(conversation, "글", true)).isFalse();
    }


    /** 완료 기다림에서 붙잡힌 turn 을 다른 스레드에서 돌린다. 루트 run 이 적힌 뒤 돌아온다. */
    private Thread startRunningTurn(String text) {
        CountDownLatch gate = release;
        stub().beforeAwait(() -> {
            try {
                gate.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        Thread running = Thread.ofVirtual().start(() -> {
            try {
                chat.stream(dad, conversation.id(), text, null, event -> {
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


    private List<ChatMessage> steerBubbles() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
            .filter(message -> message.steerState() != null)
            .toList();
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
