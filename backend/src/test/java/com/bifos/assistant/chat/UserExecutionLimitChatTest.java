package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.NextTurnDispatcher;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 사용자 실행 한도에 닿았을 때 보내기, 다시 생성, 대기 메시지 turn 이 기다리지 않고 거절되는지 본다(ADR-069).
 *
 * <p>한도를 2 로 두고, 같은 사용자의 두 대화에서 turn 을 붙잡아 자리를 채운다. 붙잡는 것은 그 turn 의 글을 담은 제출만이다.
 * 대역 전체를 붙잡으면 다른 사용자의 실행까지 멈춘다.
 */
@SpringBootTest(properties = "assistant.user-execution.max-running=2")
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class UserExecutionLimitChatTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    AgentService agentService;

    @Autowired
    TurnCancellation turns;

    @Autowired
    NextTurnDispatcher dispatcher;

    @Autowired
    ConversationEventHub hub;

    @MockitoSpyBean
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
    ChatPendingMessageRepository pendingRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    /** 글에 이 표지가 든 제출을 붙잡는다. 표지마다 제출됐다는 신호와 놓아 주는 신호가 있다. */
    private final Map<String, CountDownLatch> submitted = new ConcurrentHashMap<>();

    private final Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();
    private final List<Future<ChatTurn>> heldTurns = new CopyOnWriteArrayList<>();
    private ExecutorService pool;
    private MockMvc mvc;
    private CurrentUser dad;
    private CurrentUser mom;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        pendingRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        submitted.clear();
        releases.clear();
        heldTurns.clear();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        dad = member("dad");
        mom = member("mom");
        when(currentUser.require()).thenReturn(dad);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(
                        chat,
                        currentUser,
                        new UserDisplayNameService(users),
                        agentService,
                        access,
                        new ChatEventStreams(Duration.ofSeconds(20)),
                        null,
                        mock(ModelTierService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        stub().willAnswer(command -> {
            for (Map.Entry<String, CountDownLatch> release : releases.entrySet()) {
                if (command.input().contains(release.getKey())) {
                    submitted.get(release.getKey()).countDown();
                    await(release.getValue());
                    return completed("run-" + release.getKey(), "붙잡혔던 답");
                }
            }
            return completed("run-" + UUID.randomUUID(), "답");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        releases.values().forEach(CountDownLatch::countDown);
        for (Future<ChatTurn> turn : heldTurns) {
            turn.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }
        pool.close();
    }

    @Test
    @DisplayName("두 대화에서 turn 을 붙잡은 채 셋째 대화로 보내면 409 USER_BUSY 이고 질문도 실행 줄도 남지 않는다")
    void rejectsSendToThirdConversationWithUserBusyAndSavesNothing() throws Exception {
        Conversation third = chat.startEmpty(dad, "dad");
        holdTwoTurns();
        int submittedBefore = stub().received().size();

        mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + third.publicId() + "\",\"text\":\"셋째 질문\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_BUSY.name()));
        List<ChatEvent> streamed = new CopyOnWriteArrayList<>();
        assertUserBusy(() -> chat.stream(dad, third.id(), "셋째 질문", null, streamed::add));

        assertThat(streamed).as("스트림은 started 전에 거절된다").isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(third.id()))
                .as("셋째 대화에 저장된 메시지")
                .isEmpty();
        assertThat(executionsIn(third)).as("셋째 대화의 실행 줄").isEmpty();
        assertThat(stub().received()).as("Hermes 제출 수").hasSize(submittedBefore);
    }

    @Test
    @DisplayName("한도에 닿은 채 대화 번호 없이 보내면 USER_BUSY 이고 그 사용자의 대화 수가 늘지 않는다")
    void rejectsSendToNewConversationWithoutCreatingConversation() throws Exception {
        holdTwoTurns();
        int conversationsBefore = conversationsOf(dad).size();

        mvc.perform(post("/api/v1/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"새 대화 질문\",\"agentCode\":\"dad\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_BUSY.name()));

        assertThat(conversationsOf(dad)).as("dad 의 대화").hasSize(conversationsBefore);
    }

    @Test
    @DisplayName("미리 본 뒤 그 사이 자리가 차 잠금을 열 때 거절되면 방금 만든 빈 대화를 지운다")
    void deletesCreatedConversationWhenSlotIsTakenAfterPreCheck() {
        holdTwoTurns();
        int conversationsBefore = conversationsOf(dad).size();
        // 미리 볼 때는 자리가 있었던 것으로 둔다. 그 사이 다른 요청이 마지막 자리를 가져간 경우다.
        doReturn(true).when(limiter).hasTurnRoom(dad.id());

        assertUserBusy(() -> chat.send(dad, null, "새 대화 질문", "dad"));

        assertThat(conversationsOf(dad)).as("dad 의 대화").hasSize(conversationsBefore);
    }

    @Test
    @DisplayName("한 사용자가 한도에 닿아도 다른 사용자의 보내기는 성공한다")
    void otherUserCanSendWhileOneUserIsAtLimit() {
        holdTwoTurns();

        ChatTurn turn = chat.send(mom, null, "엄마 질문", "mom");

        assertThat(turn.assistantText()).isEqualTo("답");
    }

    @Test
    @DisplayName("붙잡은 turn 하나가 끝나면 다시 보낼 수 있고 모두 끝나면 쥔 자리가 0 이다")
    void canSendAgainAfterOneHeldTurnEndsAndUsageReturnsToZero() throws Exception {
        Conversation third = chat.startEmpty(dad, "dad");
        List<String> marks = holdTwoTurns();
        assertUserBusy(() -> chat.send(dad, third.id(), "기다리지 않는다", null));

        releases.get(marks.getFirst()).countDown();
        heldTurns.getFirst().get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        ChatTurn turn = chat.send(dad, third.id(), "다시 보낸다", null);

        assertThat(turn.assistantText()).isEqualTo("답");
        releases.get(marks.getLast()).countDown();
        heldTurns.getLast().get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(limiter.used(dad.id())).as("모든 turn 이 끝난 뒤 dad 가 쥔 자리").isZero();
    }

    @Test
    @DisplayName("한도에 닿으면 다시 생성도 USER_BUSY 이고 이전 답을 바꾸지 않는다")
    void regenerateIsRejectedWithUserBusyAndKeepsPreviousAnswer() {
        Conversation third = chat.startEmpty(dad, "dad");
        chat.send(dad, third.id(), "처음 질문", null);
        List<String> before = contents(third);
        holdTwoTurns();
        List<ChatEvent> streamed = new CopyOnWriteArrayList<>();

        assertUserBusy(() -> chat.regenerate(dad, third.id(), streamed::add));

        assertThat(streamed).as("다시 생성은 started 전에 거절된다").isEmpty();
        assertThat(contents(third)).as("셋째 대화의 메시지").containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("대기 메시지 turn 이 한도에 닿으면 대기 행을 남긴 채 멈추고 그 대화에 USER_BUSY 를 알린다")
    void pendingTurnAtLimitHoldsQueueAndPublishesUserBusy() {
        Conversation waiting = chat.startEmpty(dad, "dad");
        List<AgentExecution> fillers = fillSlotsWithRunningChildren(waiting);
        pendingRows.save(ChatPendingMessage.queued(
                waiting.id(), dad.id(), "대기 글", false, Instant.parse("2026-09-30T00:00:00Z")));
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(waiting.id(), received::add);
        try {
            dispatcher.tryNext(waiting.id());
        } finally {
            unsubscribe.run();
            executions.deleteAll(fillers);
        }

        assertThat(pendingRows.findByConversationIdOrderByIdAsc(waiting.id()))
                .as("대기 행")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.content()).isEqualTo("대기 글");
                    assertThat(row.held()).as("멈춤").isTrue();
                });
        assertThat(received).as("그 대화로 간 사건").anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("error");
            assertThat(event.code()).isEqualTo(ErrorCode.USER_BUSY.name());
        });
        assertThat(turns.markOf(waiting.id()).running()).as("열린 turn").isFalse();
        assertThat(messages.findByConversationIdOrderByIdAsc(waiting.id())).isEmpty();
        assertThat(stub().received()).as("Hermes 제출").isEmpty();
    }

    @Test
    @DisplayName("기다리다 시간 초과로 끝난 turn 은 FAILED 로 적고, Hermes 가 끝났다고 답할 때까지 사용자 자리 하나를 쥔다")
    void timedOutTurnHoldsUserSlotUntilHermesReportsFinished() {
        Conversation conversation = chat.startEmpty(dad, "dad");
        String runId = "run-timeout-" + UUID.randomUUID();
        stub().willAnswer(command -> completed(runId, "받지 못할 답"));
        stub().willLookup(runId, HermesRunLookup.running());
        // 제출은 성공하고 기다리기만 시간 초과로 끝나게 한다.
        stub().beforeAwait(() -> {
            throw new ApiException(ErrorCode.HERMES_RUN_TIMEOUT, "the agent run did not finish in time");
        });

        assertThatThrownBy(() -> chat.send(dad, conversation.id(), "오래 걸리는 질문", null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_RUN_TIMEOUT));

        assertThat(executionsIn(conversation))
                .as("시간 초과로 끝난 실행 줄")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(row.errorCode()).isEqualTo(ErrorCode.HERMES_RUN_TIMEOUT.name());
                    assertThat(row.hermesRunId()).isEqualTo(runId);
                });
        assertThat(limiter.used(dad.id())).as("turn 이 끝난 뒤 원격 종료 확인 자리").isEqualTo(1);

        stub().willLookup(runId, HermesRunLookup.finished(completed(runId, "늦게 끝난 답")));

        awaitUntil(() -> limiter.used(dad.id()) == 0, "Hermes 가 끝났다고 답한 뒤에도 dad 의 자리가 돌아오지 않았다");
        assertThat(stub().stopped()).as("끝났는지 모르는 run 에 보낸 중지").containsExactly(runId);
    }

    /** dad 의 두 대화에서 turn 을 하나씩 열어 제출에서 붙잡는다. 돌려주는 것은 붙잡은 표지다. */
    private List<String> holdTwoTurns() {
        List<String> marks = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            String mark = "붙잡기-" + index + "-" + UUID.randomUUID();
            Conversation conversation = chat.startEmpty(dad, "dad");
            submitted.put(mark, new CountDownLatch(1));
            releases.put(mark, new CountDownLatch(1));
            heldTurns.add(pool.submit(() -> chat.send(dad, conversation.id(), mark, null)));
            await(submitted.get(mark));
            marks.add(mark);
        }
        assertThat(limiter.used(dad.id())).as("붙잡은 뒤 dad 가 쥔 자리").isEqualTo(2);
        return marks;
    }

    /** 대화 turn 의 루트가 아닌 RUNNING 줄 둘로 dad 의 자리를 채운다. */
    private List<AgentExecution> fillSlotsWithRunningChildren(Conversation conversation) {
        AgentExecution root = executions.save(execution(conversation, null, ExecutionStatus.SUCCEEDED));
        List<AgentExecution> fillers = new ArrayList<>(List.of(root));
        for (int index = 0; index < 2; index++) {
            fillers.add(executions.save(execution(conversation, root.id(), ExecutionStatus.RUNNING)));
        }
        assertThat(limiter.used(dad.id())).as("채운 뒤 dad 가 쥔 자리").isEqualTo(2);
        return fillers;
    }

    private AgentExecution execution(Conversation conversation, Long parentId, ExecutionStatus status) {
        return AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(conversation.agentId())
                .parentExecutionId(parentId)
                .rootExecutionId(parentId)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(Instant.now())
                .build();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER, Instant.now()));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private List<Conversation> conversationsOf(CurrentUser user) {
        return conversations.findAll().stream()
                .filter(conversation -> user.id().equals(conversation.userId()))
                .toList();
    }

    private List<AgentExecution> executionsIn(Conversation conversation) {
        return executions.findAll().stream()
                .filter(execution -> conversation.id().equals(execution.conversationId()))
                .toList();
    }

    private List<String> contents(Conversation conversation) {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                .map(message -> message.role() + ":" + message.content())
                .toList();
    }

    private static void assertUserBusy(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.USER_BUSY);
    }

    private static HermesRunResult completed(String runId, String output) {
        return HermesRunResult.of(runId, "session", "completed", output, "model", "provider", TokenUsage.empty());
    }

    private static void awaitUntil(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError(failure + " (" + WAIT_LIMIT + " 동안 기다렸다)");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("붙잡은 turn 이 " + WAIT_LIMIT + " 안에 제출되거나 풀리지 않았다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
