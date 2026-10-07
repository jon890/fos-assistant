package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunLookup;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.SamplePriceCatalog;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
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
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/**
 * 기동할 때 {@code RUNNING} 으로 남은 실행을 Hermes 에 물어 정하는 것을 본다.
 *
 * <p>테스트 profile 은 기동 때 자동으로 돌지 않게 꺼 두었으므로 잡기와 묻기를 직접 부른다. 실행 줄은 이전 프로세스가
 * 남긴 것처럼 저장소로 직접 만든다. 묻기는 가상 스레드에서 돌므로 결과는 기다려 읽는다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
@SamplePriceCatalog
@OverrideProperties({
    // 상한을 넘겨 FAILED 로 적은 줄의 사용자 자리가 남아 있는 것을 볼 동안 돌려주지 않게 길게 둔다.
    // 그 자리를 남기는 검사는 끝나기 전에 그 run 을 모른다고 답하게 해 돌려받는다.
    "assistant.user-execution.remote-end-max-wait=30s"
})
class RestartReconcilerTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 상한을 보지 않는 검사가 주는 상한이다. 느린 머신에서도 넘지 않는다. */
    private static final Duration LONG_WAIT = Duration.ofSeconds(30);

    /** 상한을 보는 검사가 주는 상한이다. */
    private static final Duration SHORT_WAIT = Duration.ofMillis(300);

    private static final TokenUsage USAGE = new TokenUsage(1_000L, 0L, 500L, 1_500L);

    /** 표본 가격표가 아는 provider 와 모델이다. 이 짝으로 끝나야 금액이 적힌다. */
    private static final SessionRuntime PRICED_RUNTIME = new SessionRuntime("example-model-large", "anthropic");

    /** 대기 메시지 turn 과 자동 turn 의 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    /** 적다가 한 번 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    RecoveredRunRecorder recorder;

    /** 잡다가 에이전트 조회가 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    AgentService agentService;

    @Autowired
    RestartReconciler reconciler;

    @Autowired
    RestartReconcileProperties properties;

    @Autowired
    ConversationEventHub hub;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ChatService chat;

    @Autowired
    PendingMessageService pendingService;

    @Autowired
    StubHermesRunsClient stub;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatPendingMessageRepository pendingMessages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    UserExecutionLimiter limiter;

    private CurrentUser dad;
    private Agent chief;
    private Agent worker;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        awaitAllIdle();
        stub.reset();
        pendingMessages.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        AppUser user = users.save(AppUser.of("reconcile-dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        chief = agents.save(agent("dad", "비서"));
        worker = agents.save(agent("worker", "조사원"));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        // 남은 잠금이 있으면 여기서 실패한다.
        awaitAllIdle();
        stub.reset();
    }

    @Test
    @DisplayName("Hermes 에서 성공으로 끝난 대화 turn 은 사용량과 답을 적고 잠금을 푼다")
    void completedChatTurnRecordsUsageAndAnswerAndReleasesLock() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));

        reconciler.claim();
        assertThat(turns.markOf(conversation.id())).as("잡기만 한 뒤의 turn 표시").isEqualTo(new TurnMark(true, row.id()));
        assertThat(stub.lookups()).as("잡기는 Hermes 를 부르지 않는다").isEmpty();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.SUCCEEDED);
        assertThat(saved.inputTokens()).isEqualTo(1_000L);
        assertThat(saved.outputTokens()).isEqualTo(500L);
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(saved.estimatedCostMicros()).as("가격표가 아는 모델이라 금액을 적는다").isNotNull();
        awaitIdle(conversation.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.ASSISTANT, "끝난 답"));
    }

    @Test
    @DisplayName("아직 도는 대화 turn 에는 다시 붙어 잠금을 쥐고, 끝나면 답을 적고 잠금을 푼다")
    void reattachesToRunningChatTurnHoldingLockUntilItFinishes() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitLookups(row, 2);

        assertThat(statusOf(row)).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(turns.markOf(conversation.id())).isEqualTo(new TurnMark(true, row.id()));
        assertThatThrownBy(() -> chat.send(dad, conversation.id(), "그 사이 보낸 글", "dad"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_BUSY));

        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));

        awaitStatus(row, ExecutionStatus.SUCCEEDED);
        awaitIdle(conversation.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.ASSISTANT, "끝난 답"));
        assertThat(stub.stopped()).as("다시 붙은 대화 turn 은 멈추지 않는다").isEmpty();
    }

    @Test
    @DisplayName("Hermes 에서 실패로 끝난 대화 turn 은 받은 사용량을 남기고 메시지 없이 FAILED 로 적는다")
    void failedChatTurnKeepsUsageWithoutMessage() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), finished(row, "failed", null));

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("FAILED");
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        awaitIdle(conversation.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
    }

    @Test
    @DisplayName("Hermes 가 모르는 run 은 한 번만 묻고 REMOTE_RUN_LOST 로 적는다")
    void unknownRunIsAskedOnceAndRecordedAsLost() {
        AgentExecution row = chatTurn(conversation, chief);

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("REMOTE_RUN_LOST");
        awaitIdle(conversation.id());
        assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(row.id())))
                .as("묻고 실패로 적은 줄의 끝 사건")
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_FAILED, "REMOTE_RUN_LOST"));
        assertThat(stub.lookups()).containsExactly(row.hermesRunId());
    }

    @Test
    @DisplayName("Hermes 에 닿지 못하는 동안에는 RUNNING 과 잠금을 두고, 닿으면 그 답을 적는다")
    void keepsRunningAndLockWhileUnreachableThenSettles() {
        AgentExecution row = chatTurn(conversation, chief);
        ApiException failure = new ApiException(ErrorCode.HERMES_UNAVAILABLE, "hermes is down");
        stub.willFailLookup(row.hermesRunId(), failure, Integer.MAX_VALUE);

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitLookups(row, 2);

        assertThat(statusOf(row)).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(turns.markOf(conversation.id())).isEqualTo(new TurnMark(true, row.id()));

        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));
        stub.willFailLookup(row.hermesRunId(), failure, 0);

        awaitStatus(row, ExecutionStatus.SUCCEEDED);
        awaitIdle(conversation.id());
    }

    @Test
    @DisplayName("run 번호가 없는 줄은 묻지 않고 ORPHANED 로 적는다")
    void rowWithoutRunIdIsRecordedAsOrphanedWithoutAsking() {
        AgentExecution row = executions.save(running(conversation, chief).build());

        reconciler.claim();
        assertThat(turns.markOf(conversation.id()).running())
                .as("run 번호가 없는 줄은 잠금을 잡지 않는다")
                .isFalse();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("ORPHANED");
        assertThat(stub.lookups()).isEmpty();
    }

    @Test
    @DisplayName("에이전트 행이 없는 줄은 묻지 않고 ORPHANED 로 적고 잡은 잠금을 푼다")
    void rowWithoutAgentIsRecordedAsOrphanedAndLockReleased() {
        AgentExecution row = chatTurn(conversation, chief);
        agents.deleteById(chief.id());

        reconciler.claim();
        assertThat(turns.markOf(conversation.id()).running()).isTrue();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("ORPHANED");
        awaitIdle(conversation.id());
        assertThat(stub.lookups()).isEmpty();
    }

    @Test
    @DisplayName("상한까지 계속 돌면 중지를 보내고 RECONCILE_TIMEOUT 으로 적는다")
    void stopsAndRecordsTimeoutWhenStillRunningAtLimit() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());

        reconciler.claim();
        reconciler.reconcile(SHORT_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("RECONCILE_TIMEOUT");
        assertThat(stub.stopped()).containsExactly(row.hermesRunId());
        awaitIdle(conversation.id());
        releaseRemoteEndHold(row);
    }

    @Test
    @DisplayName("상한까지 한 번도 닿지 못하면 RECONCILE_UNREACHABLE 로 적는다")
    void recordsUnreachableWhenNeverReachedUntilLimit() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willFailLookup(
                row.hermesRunId(), new ApiException(ErrorCode.HERMES_UNAVAILABLE, "hermes is down"), Integer.MAX_VALUE);

        reconciler.claim();
        reconciler.reconcile(SHORT_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("RECONCILE_UNREACHABLE");
        awaitIdle(conversation.id());
        releaseRemoteEndHold(row);
    }

    @Test
    @DisplayName("상한을 넘겨 FAILED 로 적은 줄의 사용자 자리는 Hermes 가 그 run 을 모른다고 답할 때까지 남고, 중지는 한 번만 간다")
    void keepsUserSlotAfterGiveUpUntilHermesForgetsRun() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());

        reconciler.claim();
        reconciler.reconcile(SHORT_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("RECONCILE_TIMEOUT");
        awaitIdle(conversation.id());
        // 기동 정리는 상한을 넘긴 뒤 더 묻지 않는다. 그 뒤의 조회는 원격 종료 확인이 한 것이다.
        long askedByReconciler = lookupsOf(row);
        awaitLookups(row, Math.toIntExact(askedByReconciler + 2));
        assertThat(limiter.used(dad.id())).as("turn 잠금을 푼 뒤 남은 원격 종료 확인 자리").isEqualTo(1);

        stub.willLookup(row.hermesRunId(), HermesRunLookup.notFound());

        awaitUntil(() -> limiter.used(dad.id()) == 0, "Hermes 가 모른다고 답한 뒤에도 dad 의 자리가 돌아오지 않았다");
        assertThat(stub.stopped()).as("기동 정리가 보낸 중지 하나뿐이다").containsExactly(row.hermesRunId());
    }

    @Test
    @DisplayName("아직 도는 위임 실행에는 부모 대화를 잠그지 않고 다시 붙고, 끝난 결과를 부모 대화에 한 번 전한다")
    void reattachesToRunningDelegationWithoutLockAndDeliversResultOnce() {
        AgentExecution row = delegated();
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        stub.willReturn(HermesRunResult.of("auto", "session", "completed", "정리한 답", "model", "provider", USAGE));

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitLookups(row, 2);

        assertThat(statusOf(row)).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(turns.markOf(conversation.id()).running())
                .as("위임 실행은 부모 대화의 잠금을 잡지 않는다")
                .isFalse();

        stub.willLookup(row.hermesRunId(), finished(row, "completed", "조사 결과"));

        AgentExecution saved = awaitStatus(row, ExecutionStatus.SUCCEEDED);
        assertThat(saved.outputText()).isEqualTo("조사 결과");
        awaitUntil(() -> delivered(row), "위임 결과가 부모 대화에 전해지지 않았다");
        awaitIdle(conversation.id());
        assertThat(notices()).as("부모 대화의 알림 줄").hasSize(1);
        assertThat(stub.stopped()).isEmpty();
    }

    @Test
    @DisplayName("같은 줄에 잡기와 묻기를 두 번 해도 한 번만 묻고 한 번만 적는다")
    void claimingAndReconcilingTwiceAsksAndWritesOnce() {
        AgentExecution turn = chatTurn(conversation, chief);
        AgentExecution child = delegated();
        stub.willLookup(turn.hermesRunId(), finished(turn, "completed", "끝난 답"));
        stub.willLookup(child.hermesRunId(), finished(child, "completed", "조사 결과"));
        stub.willReturn(HermesRunResult.of("auto", "session", "completed", "정리한 답", "model", "provider", USAGE));

        reconciler.claim();
        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        reconciler.reconcile(LONG_WAIT);

        AgentExecution first = awaitStatus(turn, ExecutionStatus.SUCCEEDED);
        awaitStatus(child, ExecutionStatus.SUCCEEDED);
        awaitUntil(() -> delivered(child), "위임 결과가 부모 대화에 전해지지 않았다");
        awaitIdle(conversation.id());
        Instant deliveredAt = executions.findById(child.id()).orElseThrow().resultDeliveredAt();

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitIdle(conversation.id());

        assertThat(stub.lookups())
                .as("끝난 줄은 다시 묻지 않는다")
                .containsExactlyInAnyOrder(turn.hermesRunId(), child.hermesRunId());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .filteredOn(message -> turn.id().equals(message.executionId()))
                .as("그 실행의 답")
                .hasSize(1);
        assertThat(notices()).as("부모 대화의 알림 줄").hasSize(1);
        AgentExecution again = executions.findById(turn.id()).orElseThrow();
        assertThat(again.totalTokens()).isEqualTo(first.totalTokens());
        assertThat(again.finishedAt()).isEqualTo(first.finishedAt());
        assertThat(executions.findById(child.id()).orElseThrow().resultDeliveredAt())
                .isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("아직 도는 흐름 turn 의 루트 줄에는 중지를 보내고 취소로 끝난 상태를 적는다")
    void stopsRunningFlowRootAndRecordsCancelled() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        Conversation flowConversation =
                conversations.save(Conversation.startedBy(dad.id(), "흐름 대화", flowed.id(), Instant.now()));
        AgentExecution row = chatTurn(flowConversation, flowed);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        stub.onStop(runId -> stub.willLookup(runId, finished(row, "cancelled", "")));

        reconciler.claim();
        assertThat(turns.markOf(flowConversation.id())).isEqualTo(new TurnMark(true, row.id()));
        reconciler.reconcile(LONG_WAIT);

        awaitStatus(row, ExecutionStatus.CANCELLED);
        awaitIdle(flowConversation.id());
        assertThat(stub.stopped()).containsExactly(row.hermesRunId());
        assertThat(messages.findByConversationIdOrderByIdAsc(flowConversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("다시 붙은 대화 turn 을 사용자가 멈추면 중지를 보내고 CANCELLED 로 적은 뒤 대기 줄을 멈춘다")
    void userStopOnReattachedTurnCancelsAndHoldsPendingQueue() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        pendingMessages.save(ChatPendingMessage.queued(conversation.id(), dad.id(), "기다리는 글", false, Instant.now()));

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitLookups(row, 1);

        chat.stop(dad, row.id());
        assertThat(stub.stopped()).containsExactly(row.hermesRunId());
        stub.willLookup(row.hermesRunId(), finished(row, "cancelled", "일부 답"));

        awaitStatus(row, ExecutionStatus.CANCELLED);
        awaitIdle(conversation.id());
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatPendingMessage::content, ChatPendingMessage::held)
                .containsExactly(tuple("기다리는 글", true));
        assertThat(stub.received()).as("멈춘 대기 줄은 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("중지를 요청만 하고 확정하기 전에 취소 결과가 적혀도 대기 줄을 멈춘다")
    void holdsPendingQueueWhenCancelledResultArrivesBeforeStopIsConfirmed() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        pendingMessages.save(ChatPendingMessage.queued(conversation.id(), dad.id(), "기다리는 글", false, Instant.now()));

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        awaitLookups(row, 1);

        // 사용자의 중지가 Hermes 에 중지를 보낸 뒤 확정하기 전인 상태다.
        TurnHandle handle = turns.find(row.id()).orElseThrow();
        turns.cancel(handle);
        assertThat(turns.isStopConfirmed(handle)).as("아직 확정 전이다").isFalse();
        stub.willLookup(row.hermesRunId(), finished(row, "cancelled", "일부 답"));

        awaitStatus(row, ExecutionStatus.CANCELLED);
        awaitIdle(conversation.id());
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatPendingMessage::content, ChatPendingMessage::held)
                .containsExactly(tuple("기다리는 글", true));
        assertThat(stub.received()).as("멈춘 대기 줄은 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("정하는 동안 쌓인 대기 메시지는 남아 있다가 잠금이 풀린 뒤 새 turn 으로 간다")
    void pendingMessageQueuedWhileReconcilingIsSentAfterLockRelease() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        stub.willReturn(HermesRunResult.of("next", "session", "completed", "다음 답", "model", "provider", USAGE));

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);
        pendingService.enqueue(dad, conversation.id(), "쌓인 글");
        awaitLookups(row, 2);

        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatPendingMessage::content)
                .containsExactly("쌓인 글");
        assertThat(stub.received()).as("잠금이 잡힌 동안에는 보내지 않는다").isEmpty();

        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));

        awaitUntil(
                () -> messages.findByConversationIdOrderByIdAsc(conversation.id())
                                .size()
                        == 3,
                "쌓인 글의 turn 이 끝나지 않았다");
        awaitIdle(conversation.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(
                        tuple(MessageRole.ASSISTANT, "끝난 답"),
                        tuple(MessageRole.USER, "쌓인 글"),
                        tuple(MessageRole.ASSISTANT, "다음 답"));
        assertThat(stub.received()).as("새 실행이 한 번 제출됐다").hasSize(1);
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("내려가기 시작한 뒤에는 줄을 적지 않고 중지도 보내지 않는다")
    void writesNothingAfterStop() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.running());
        try {
            reconciler.claim();
            reconciler.reconcile(SHORT_WAIT);
            awaitLookups(row, 1);

            reconciler.stop();
            stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));
            // 묻던 스레드가 끝난 뒤에 본다. 끝난 뒤에는 줄을 적을 것이 남아 있지 않다.
            awaitUntil(() -> reconciler.activeThreads() == 0, "묻던 스레드가 끝나지 않았다");

            assertThat(reconciler.isRunning()).isFalse();
            assertThat(statusOf(row)).as("다음 기동이 다시 정하도록 남긴다").isEqualTo(ExecutionStatus.RUNNING);
            assertThat(stub.stopped()).isEmpty();
            assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                    .isEmpty();
        } finally {
            // 이 빈은 캐시된 문맥이 함께 쓴다. 내려가는 중 표시를 지워 뒤 검사가 정상으로 돌게 한다.
            reconciler.start();
        }
        assertThat(reconciler.isRunning()).isTrue();
    }

    @Test
    @DisplayName("적다가 한 번 실패하면 실패로 적지 않고 다시 물어 그 답을 적는다")
    void asksAgainWhenWritingFailsOnce() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));
        doThrow(new IllegalStateException("일시적인 DB 오류"))
                .doCallRealMethod()
                .when(recorder)
                .settle(any(), any());

        reconciler.claim();
        reconciler.reconcile(LONG_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.SUCCEEDED);
        assertThat(saved.errorCode()).isNull();
        awaitIdle(conversation.id());
        assertThat(stub.lookups()).as("실패한 뒤 한 번 더 물었다").hasSize(2);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly("끝난 답");
    }

    @Test
    @DisplayName("끝난 답을 계속 적지 못해 상한을 넘기면 중지를 보내지 않고 RECONCILE_TIMEOUT 으로 적는다")
    void recordsTimeoutWithoutStopWhenFinishedAnswerCannotBeWritten() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));
        doThrow(new IllegalStateException("계속되는 DB 오류")).when(recorder).settle(any(), any());

        reconciler.claim();
        reconciler.reconcile(SHORT_WAIT);

        AgentExecution saved = awaitStatus(row, ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).as("Hermes 의 답은 받았으므로 닿지 못한 것이 아니다").isEqualTo("RECONCILE_TIMEOUT");
        awaitIdle(conversation.id());
        assertThat(stub.stopped()).as("이미 끝난 run 에는 중지를 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("잡다가 에이전트 조회가 실패한 줄의 대화는 잠긴 채 남지 않고, 다시 잡으면 한 번만 센다")
    void conversationIsNotLeftLockedWhenClaimFailsAndRetryCountsOnce() {
        AgentExecution row = chatTurn(conversation, chief);
        stub.willLookup(row.hermesRunId(), finished(row, "completed", "끝난 답"));
        doThrow(new IllegalStateException("일시적인 DB 오류"))
                .doCallRealMethod()
                .when(agentService)
                .findById(chief.id());

        assertThatThrownBy(() -> reconciler.claim()).isInstanceOf(IllegalStateException.class);
        assertThat(turns.markOf(conversation.id()).running())
                .as("잡다가 실패한 줄의 잠금")
                .isFalse();

        reconciler.claim();
        assertThat(turns.markOf(conversation.id())).isEqualTo(new TurnMark(true, row.id()));
        reconciler.reconcile(LONG_WAIT);

        awaitStatus(row, ExecutionStatus.SUCCEEDED);
        // 남은 줄 수를 두 번 셌다면 한 줄이 정해져도 잠금이 풀리지 않는다.
        awaitIdle(conversation.id());
    }

    @Test
    @DisplayName("루트 줄이 끝나고 자식만 도는 흐름 turn 도 대화를 잠그고, 자식을 멈춘 뒤에 푼다")
    void locksConversationForRunningFlowChildUntilItIsStopped() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        Conversation flowConversation =
                conversations.save(Conversation.startedBy(dad.id(), "흐름 대화", flowed.id(), Instant.now()));
        AgentExecution root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(flowConversation.id())
                .agentId(flowed.id())
                .profileName(flowed.hermesProfile())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(
                        Instant.now().minus(Duration.ofMinutes(2)),
                        Instant.now().minus(Duration.ofMinutes(1)))
                .build());
        AgentExecution child = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(flowConversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .profileName("worker")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build());
        stub.willLookup(child.hermesRunId(), HermesRunLookup.running());
        stub.onStop(runId -> stub.willLookup(runId, finished(child, "cancelled", "")));

        reconciler.claim();

        assertThat(turns.markOf(flowConversation.id()))
                .as("표시에는 루트 실행 번호가 붙는다")
                .isEqualTo(new TurnMark(true, root.id()));
        assertThatThrownBy(() -> chat.send(dad, flowConversation.id(), "그 사이 보낸 글", "flowed"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_BUSY));

        reconciler.reconcile(LONG_WAIT);

        awaitStatus(child, ExecutionStatus.CANCELLED);
        awaitIdle(flowConversation.id());
        assertThat(stub.stopped()).containsExactly(child.hermesRunId());
        assertThat(statusOf(root)).as("끝난 루트 줄은 그대로다").isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("자식만 돌던 흐름 turn 을 사용자가 멈췄으면 풀 때 stopped 를 내고 대기 줄을 멈춘다")
    void publishesStoppedAndHoldsPendingWhenUserStoppedChildOnlyFlowTurn() {
        Conversation flowConversation = flowConversation();
        AgentExecution child = runningFlowChild(flowConversation);
        stub.willLookup(child.hermesRunId(), finished(child, "cancelled", ""));
        pendingMessages.save(
                ChatPendingMessage.queued(flowConversation.id(), dad.id(), "기다리는 글", false, Instant.now()));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        Runnable stopListening = hub.subscribe(flowConversation.id(), events::add);

        try {
            reconciler.claim();
            turns.cancel(turns.find(child.treeRootId()).orElseThrow());
            reconciler.reconcile(LONG_WAIT);
            awaitStatus(child, ExecutionStatus.CANCELLED);
            awaitIdle(flowConversation.id());
        } finally {
            stopListening.run();
        }

        assertThat(events)
                .filteredOn(event -> !"pending".equals(event.type()))
                .extracting(ChatEvent::type, ChatEvent::messageId, ChatEvent::executionId)
                .containsExactly(tuple("stopped", null, child.treeRootId()));
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(flowConversation.id()))
                .extracting(ChatPendingMessage::content, ChatPendingMessage::held)
                .containsExactly(tuple("기다리는 글", true));
        assertThat(stub.received()).as("멈춘 대기 줄은 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("자식만 돌던 흐름 turn 이 사용자의 중지 없이 끝나면 풀 때 error 를 한 번 낸다")
    void publishesErrorOnceWhenChildOnlyFlowTurnEndsWithoutUserStop() {
        Conversation flowConversation = flowConversation();
        AgentExecution child = runningFlowChild(flowConversation);
        stub.willLookup(child.hermesRunId(), HermesRunLookup.running());
        stub.onStop(runId -> stub.willLookup(runId, finished(child, "cancelled", "")));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        Runnable stopListening = hub.subscribe(flowConversation.id(), events::add);

        try {
            reconciler.claim();
            reconciler.reconcile(LONG_WAIT);
            awaitStatus(child, ExecutionStatus.CANCELLED);
            awaitIdle(flowConversation.id());
        } finally {
            stopListening.run();
        }

        assertThat(events)
                .extracting(ChatEvent::type, ChatEvent::code)
                .containsExactly(tuple("error", "HERMES_RUN_FAILED"));
    }

    @Test
    @DisplayName("기동 때의 잡기와 다시 잡기가 모두 도중에 실패해도 이미 잡은 줄은 묻고 잠금을 푼다")
    void asksAlreadyClaimedRowsEvenWhenClaimAndRetryBothFail() {
        AgentExecution claimedRow = chatTurn(conversation, chief);
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "다른 대화", worker.id(), Instant.now()));
        AgentExecution failingRow = chatTurn(other, worker);
        stub.willLookup(claimedRow.hermesRunId(), finished(claimedRow, "completed", "끝난 답"));
        stub.willLookup(failingRow.hermesRunId(), finished(failingRow, "completed", "묻지 않는 답"));
        doThrow(new IllegalStateException("계속되는 DB 오류")).when(agentService).findById(worker.id());
        // 기준 시각을 지금으로 옮긴다. 테스트 profile 은 꺼 두었으므로 여기서 잡지는 않는다.
        reconciler.start();

        assertThatThrownBy(() -> reconciler.claim()).isInstanceOf(IllegalStateException.class);
        assertThat(turns.markOf(conversation.id()).running()).as("먼저 잡은 줄의 잠금").isTrue();
        reconciler.resumeAfterStart();

        awaitStatus(claimedRow, ExecutionStatus.SUCCEEDED);
        awaitIdle(conversation.id());
        assertThat(turns.markOf(other.id()).running()).as("잡지 못한 줄의 대화").isFalse();
        assertThat(statusOf(failingRow)).as("다음 기동이 정하도록 남긴다").isEqualTo(ExecutionStatus.RUNNING);
        assertThat(stub.lookups()).containsExactly(claimedRow.hermesRunId());
    }

    @Test
    @DisplayName("웹 서버를 여는 lifecycle 보다 먼저 시작한다")
    void startsBeforeWebServerLifecycle() {
        assertThat(reconciler.getPhase()).isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
    }

    @Test
    @DisplayName("상한 설정은 비우면 null 이고 0 이하이면 설정 이름을 넣어 거절한다")
    void maxWaitIsNullWhenBlankAndRejectedWhenNotPositive() {
        assertThat(properties.maxWait()).as("비워 둔 기본 설정").isNull();
        assertThat(new RestartReconcileProperties(true, Duration.ofMillis(1)).maxWait())
                .isEqualTo(Duration.ofMillis(1));
        assertThatThrownBy(() -> new RestartReconcileProperties(true, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistant.restart-reconcile.max-wait");
    }

    private Conversation flowConversation() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        return conversations.save(Conversation.startedBy(dad.id(), "흐름 대화", flowed.id(), Instant.now()));
    }

    /** 루트 줄은 이미 성공으로 끝났고 자식만 아직 도는 흐름 turn 의 자식 줄이다. */
    private AgentExecution runningFlowChild(Conversation flowConversation) {
        AgentExecution root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(flowConversation.id())
                .agentId(flowConversation.agentId())
                .profileName("flowed")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(
                        Instant.now().minus(Duration.ofMinutes(2)),
                        Instant.now().minus(Duration.ofMinutes(1)))
                .build());
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(flowConversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .profileName("worker")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build());
    }

    /** 이전 프로세스가 돌리던 대화 turn 의 루트 줄이다. */
    private AgentExecution chatTurn(Conversation owner, Agent agent) {
        return executions.save(
                running(owner, agent).hermesRunId("run-" + UUID.randomUUID()).build());
    }

    private AgentExecution.Builder running(Conversation owner, Agent agent) {
        return AgentExecution.builder()
                .userId(dad.id())
                .conversationId(owner.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofMinutes(1)));
    }

    /** 끝난 대화 turn 아래에서 다른 에이전트에게 맡겨 아직 도는 실행이다. */
    private AgentExecution delegated() {
        AgentExecution root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .delegationKey(UUID.randomUUID().toString())
                .profileName("worker")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .build());
    }

    private Agent agent(String code, String name) {
        return Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                dad.id(),
                Instant.now());
    }

    private static HermesRunLookup finished(AgentExecution row, String status, String output) {
        return HermesRunLookup.finished(new HermesRunResult(
                row.hermesRunId(), "sess-1", status, output, null, null, null, USAGE, PRICED_RUNTIME));
    }

    private ExecutionStatus statusOf(AgentExecution row) {
        return executions.findById(row.id()).orElseThrow().status();
    }

    private boolean delivered(AgentExecution row) {
        return executions.findById(row.id()).orElseThrow().resultDeliveredAt() != null;
    }

    private List<ChatMessage> notices() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .toList();
    }

    /**
     * 상한을 넘겨 FAILED 로 적은 줄의 원격 종료 확인 자리를 돌려받는다.
     *
     * <p>그 run 을 모른다고 답하게 하고 자리가 돌아올 때까지 기다린다. 확인 스레드가 검사 뒤까지 남아 다음 검사의 조회 기록에
     * 섞이지 않게 한다.
     */
    private void releaseRemoteEndHold(AgentExecution row) {
        stub.willFailLookup(row.hermesRunId(), new ApiException(ErrorCode.HERMES_UNAVAILABLE, "hermes is down"), 0);
        stub.willLookup(row.hermesRunId(), HermesRunLookup.notFound());
        awaitUntil(() -> limiter.used(dad.id()) == 0, "run " + row.hermesRunId() + " 의 원격 종료 확인 자리가 돌아오지 않았다");
    }

    private long lookupsOf(AgentExecution row) {
        return stub.lookups().stream().filter(row.hermesRunId()::equals).count();
    }

    /** 그 run 을 적어도 {@code count} 번 물을 때까지 기다린다. 되풀이해 묻고 있다는 것을 본 뒤에 단언하려고 쓴다. */
    private void awaitLookups(AgentExecution row, int count) {
        awaitUntil(
                () -> stub.lookups().stream().filter(row.hermesRunId()::equals).count() >= count,
                "run " + row.hermesRunId() + " 을 " + count + "번 묻지 않았다");
    }

    /** 그 줄이 기대한 상태가 될 때까지 기다린 뒤 다시 읽어 돌려준다. */
    private AgentExecution awaitStatus(AgentExecution row, ExecutionStatus expected) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (true) {
            AgentExecution saved = executions.findById(row.id()).orElseThrow();
            if (saved.status() == expected) {
                return saved;
            }
            if (System.nanoTime() > deadline) {
                fail(
                        "실행 %d 이 %s 안에 %s 가 되지 않았다. 지금은 %s(%s) 다",
                        row.id(), WAIT_LIMIT, expected, saved.status(), saved.errorCode());
            }
            pause();
        }
    }

    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitIdle(it.id()));
    }

    /** 그 대화에 도는 turn 이 없어질 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private void awaitIdle(Long conversationId) {
        awaitUntil(() -> !turns.markOf(conversationId).running(), "대화 " + conversationId + " 의 turn 잠금이 풀리지 않았다");
    }

    private void awaitUntil(BooleanSupplier condition, String failure) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("%s (%s 동안 기다렸다)", failure, WAIT_LIMIT);
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
