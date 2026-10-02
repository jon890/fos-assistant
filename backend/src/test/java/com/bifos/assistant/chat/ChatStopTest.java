package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnClosed;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatStopTest {

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

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

    @MockitoSpyBean
    TurnCancellation turns;

    @MockitoBean
    HermesRunEventStream eventStream;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, Instant.now()));
        agents.save(Agent.of(
                profileName,
                profileName,
                profileName,
                "http://agent-runtime.test/p/" + profileName,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private void hermesStreams(RunEvent... events) {
        doAnswer(invocation -> {
                    Consumer<RunEvent> onEvent = invocation.getArgument(3);
                    for (RunEvent event : events) {
                        onEvent.accept(event);
                    }
                    return null;
                })
                .when(eventStream)
                .open(any(), any(), any(), any(), any(), anyBoolean());
    }

    private AgentExecution latestExecution(CurrentUser user) {
        return executions
                .findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 1))
                .getFirst();
    }

    private List<ExecutionEventType> eventTypes(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                .map(ExecutionEvent::eventType)
                .toList();
    }

    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("도는 turn을 멈추면 취소 상태와 Hermes 결과를 남긴다")
    void stoppingRunningTurnLeavesCancelledStateAndHermesResult() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "절반", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "멈춰 줘", "dad", relayed::add);

        ChatEvent stopped = relayed.getLast();
        assertThat(stopped.type()).isEqualTo("stopped");
        assertThat(stub().stopped()).containsExactly("run-stop");
        assertThat(executions.findById(stopped.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(dad, stopped.conversationId())))
                .last()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
                    assertThat(message.content()).isEqualTo("절반");
                });
        assertThat(eventTypes(stopped.executionId())).contains(ExecutionEventType.RUN_CANCELLED);
    }

    @Test
    @DisplayName("중지로 끝난 turn 은 닫힐 때 stopped 가 참이다")
    void stoppedTurnClosesWithStoppedTrue() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "절반", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));
        List<TurnClosed> closed = new CopyOnWriteArrayList<>();
        turns.addCloseListener(closed::add);

        ChatTurn turn = chat.send(dad, null, "멈춰 줘", "dad");

        Long conversationId =
                executions.findById(turn.executionId()).orElseThrow().conversationId();
        assertThat(closed)
                .filteredOn(it -> it.conversationId().equals(conversationId))
                .singleElement()
                .satisfies(it -> assertThat(it.stopped()).isTrue());
    }

    @Test
    @DisplayName("중지하지 않고 끝난 turn 은 닫힐 때 stopped 가 거짓이다")
    void finishedTurnClosesWithStoppedFalse() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-done", "session", "completed", "완료", "model", "provider", TokenUsage.empty()));
        List<TurnClosed> closed = new CopyOnWriteArrayList<>();
        turns.addCloseListener(closed::add);

        ChatTurn turn = chat.send(dad, null, "완료해 줘", "dad");

        Long conversationId =
                executions.findById(turn.executionId()).orElseThrow().conversationId();
        assertThat(closed)
                .filteredOn(it -> it.conversationId().equals(conversationId))
                .singleElement()
                .satisfies(it -> assertThat(it.stopped()).isFalse());
    }

    @Test
    @DisplayName("중지한 결과의 output이 비면 스트림 조각을 답으로 남긴다")
    void usesStreamChunksAsReplyWhenStoppedOutputIsEmpty() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        hermesStreams(new RunEvent("message.delta", "앞부분", null, null, null, null));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "멈춰 줘", "dad", relayed::add);

        assertThat(messages.findByConversationIdOrderByIdAsc(
                        access.requireOwnId(dad, relayed.getLast().conversationId())))
                .last()
                .satisfies(message -> assertThat(message.content()).isEqualTo("앞부분"));
    }

    @Test
    @DisplayName("중지한 결과와 스트림 조각이 모두 비면 답 메시지를 만들지 않는다")
    void createsNoReplyMessageWhenStoppedOutputAndChunksAreBothEmpty() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "멈춰 줘", "dad", relayed::add);

        ChatEvent stopped = relayed.getLast();
        assertThat(stopped.type()).isEqualTo("stopped");
        assertThat(stopped.messageId()).isNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(dad, stopped.conversationId())))
                .singleElement()
                .satisfies(message -> assertThat(message.role()).isEqualTo(MessageRole.USER));
    }

    @Test
    @DisplayName("다른 사용자의 도는 실행은 찾을 수 없다고 응답한다")
    void respondsNotFoundForOtherUsersRunningExecution() {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> {
            assertThatThrownBy(() -> chat.stop(mom, latestExecution(dad).id()))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.EXECUTION_NOT_FOUND);
            chat.stop(dad, latestExecution(dad).id());
        });

        ChatTurn turn = chat.send(dad, null, "멈춰 줘", "dad");

        assertThat(executions.findById(turn.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    @DisplayName("끝난 실행을 멈추면 실행이 끝났다고 응답한다")
    void stoppingFinishedRunRespondsThatItEnded() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-done", "session", "completed", "완료", "model", "provider", TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "완료해 줘", "dad");

        assertThatThrownBy(() -> chat.stop(dad, turn.executionId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.EXECUTION_NOT_RUNNING);
    }

    @Test
    @DisplayName("중지 전송이 실패하면 다음 요청이 같은 run에 다시 보낸다")
    void nextRequestResendsToSameRunWhenStopSendFails() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-retry", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        AtomicInteger attempts = new AtomicInteger();
        stub().onStop(runId -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "temporary failure");
            }
        });
        stub().beforeAwait(() -> {
            Long executionId = latestExecution(dad).id();
            assertThatThrownBy(() -> chat.stop(dad, executionId))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
            chat.stop(dad, executionId);
        });

        chat.send(dad, null, "멈춰 줘", "dad");

        assertThat(stub().stopped()).containsExactly("run-retry", "run-retry");
    }

    @Test
    @DisplayName("Hermes 중지 전송이 실패하면 실행과 스트림을 계속 받는다")
    void keepsReceivingRunAndStreamWhenHermesStopSendFails() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-continue", "session", "completed", "계속한 답", "model", "provider", TokenUsage.empty()));
        stub().onStop(runId -> {
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "stop failed");
        });
        doAnswer(invocation -> {
                    Consumer<RunEvent> onEvent = invocation.getArgument(3);
                    assertThatThrownBy(() -> chat.stop(dad, latestExecution(dad).id()))
                            .isInstanceOf(ApiException.class)
                            .extracting(ex -> ((ApiException) ex).code())
                            .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
                    onEvent.accept(new RunEvent("message.delta", "조각", null, null, null, null));
                    return null;
                })
                .when(eventStream)
                .open(any(), any(), any(), any(), any(), anyBoolean());

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "계속해 줘", "dad", relayed::add);

        assertThat(relayed)
                .extracting(ChatEvent::type)
                .contains("delta", "done")
                .doesNotContain("stopped");
        assertThat(executions
                        .findById(relayed.getLast().executionId())
                        .orElseThrow()
                        .status())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("에이전트 행이 없는 자식 실행은 건너뛰고 루트 실행을 취소로 끝낸다")
    void skipsChildRunWithoutAgentRowAndEndsRootRunAsCancelled() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-root", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> {
            AgentExecution root = latestExecution(dad);
            // 에이전트 번호가 가리키는 행이 없는 자식이다. 같은 루트를 가리키며 아직 돈다.
            executions.save(AgentExecution.builder()
                    .userId(dad.id())
                    .agentId(-1L)
                    .parentExecutionId(root.id())
                    .rootExecutionId(root.id())
                    .profileName("gone")
                    .hermesRunId("run-child")
                    .costMode(CostMode.SUBSCRIPTION)
                    .status(ExecutionStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build());
            chat.stop(dad, root.id());
        });

        ChatTurn turn = chat.send(dad, null, "자식이 있는 채로 멈춰 줘", "dad");

        assertThat(executions.findById(turn.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(stub().stopped()).containsExactly("run-root");
    }

    @Test
    @DisplayName("중지 요청을 두 번 받아도 성공한 run에는 한 번만 보낸다")
    void sendsToSucceededRunOnlyOnceEvenIfStopRequestedTwice() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-once", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> {
            Long executionId = latestExecution(dad).id();
            chat.stop(dad, executionId);
            chat.stop(dad, executionId);
        });

        chat.send(dad, null, "한 번만 멈춰 줘", "dad");

        assertThat(stub().stopped()).containsExactly("run-once");
    }

    @Test
    @DisplayName("제출 전에 중지 전송이 실패해도 다음 중지 요청이 같은 run에 다시 보낸다")
    void nextStopResendsToSameRunEvenIfStopSendBeforeSubmitFails() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> firstStop = new AtomicReference<>();
        CountDownLatch awaitingFirstStop = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        stub().onStop(runId -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "temporary failure");
            }
        });
        doAnswer(invocation -> {
                    awaitingFirstStop.countDown();
                    return invocation.callRealMethod();
                })
                .when(turns)
                .awaitFirstStop(any());
        // 첫 중지 요청이 run 없이 첫 중지 결과를 기다리기 시작한 뒤에 제출을 끝낸다.
        // 제출이 먼저 run 을 등록하면 그 요청이 run 을 직접 다시 멈춰 성공할 수 있다.
        stub().willAnswer(command -> {
            Long executionId = latestExecution(dad).id();
            firstStop.set(executor.submit(() -> chat.stop(dad, executionId)));
            try {
                assertThat(awaitingFirstStop.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            return HermesRunResult.of(
                    "run-before-submit-retry", "session", "cancelled", "", "model", "provider", TokenUsage.empty());
        });
        stub().beforeAwait(() -> {
            assertThatThrownBy(() -> firstStop.get().get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(ex ->
                            assertThat(((ApiException) ex.getCause()).code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
            chat.stop(dad, latestExecution(dad).id());
        });
        try {
            chat.send(dad, null, "제출 전에 실패해도 멈춰 줘", "dad");
        } finally {
            executor.shutdownNow();
        }

        assertThat(stub().stopped()).containsExactly("run-before-submit-retry", "run-before-submit-retry");
    }

    @Test
    @DisplayName("제출이 보낸 중지가 실패해도 같은 중지 요청이 다시 보내 성공하면 성공으로 답한다")
    void answersSuccessWhenSameStopRequestResendsAfterSubmitSentStopFailed() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> firstStop = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        stub().onStop(runId -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "temporary failure");
            }
        });
        // 중지 요청이 취소 표시를 남긴 뒤 run 이 있는지 보는 것을, 제출이 run 을 등록하며 보낸 중지가 실패할 때까지 미룬다.
        // 그 요청이 같은 run 에 다시 보내 Hermes 가 받아들였으므로 중지는 성공이다.
        doAnswer(invocation -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    while (attempts.get() == 0 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    return invocation.callRealMethod();
                })
                .when(turns)
                .hasRuns(any());
        stub().willAnswer(command -> {
            Long executionId = latestExecution(dad).id();
            firstStop.set(executor.submit(() -> chat.stop(dad, executionId)));
            assertThat(awaitCancelled(executionId)).isTrue();
            return HermesRunResult.of(
                    "run-retried-by-stop", "session", "completed", "", "model", "provider", TokenUsage.empty());
        });
        stub().beforeAwait(() -> {
            try {
                firstStop.get().get(1, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
        ChatTurn turn;
        try {
            turn = chat.send(dad, null, "제출과 중지가 겹쳐도 멈춰 줘", "dad");
        } finally {
            executor.shutdownNow();
        }

        assertThat(stub().stopped()).containsExactly("run-retried-by-stop", "run-retried-by-stop");
        assertThat(turn.cancelled()).isTrue();
        assertThat(executions.findById(turn.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    @DisplayName("제출 전에 run 없음을 본 중지 요청이 같은 run을 다시 멈추면 첫 중지가 실패했어도 성공으로 답한다")
    void answersSuccessWhenStopThatSawNoRunBeforeSubmitStopsSameRunAgain() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> firstStop = new AtomicReference<>();
        CountDownLatch sawNoRuns = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        stub().onStop(runId -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "temporary failure");
            }
        });
        // 중지 요청이 run 이 없다고 본 뒤, 제출이 run 을 등록하며 보낸 첫 중지가 실패할 때까지 다시 보내기를 미룬다.
        // 그 뒤 요청이 같은 run 에 다시 보내 Hermes 가 받아들였으므로 중지는 성공이다.
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    sawNoRuns.countDown();
                    return result;
                })
                .when(turns)
                .hasRuns(any());
        doAnswer(invocation -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    while (attempts.get() == 0 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    return invocation.callRealMethod();
                })
                .when(turns)
                .pendingStops(any());
        stub().willAnswer(command -> {
            Long executionId = latestExecution(dad).id();
            firstStop.set(executor.submit(() -> chat.stop(dad, executionId)));
            try {
                assertThat(sawNoRuns.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            return HermesRunResult.of(
                    "run-raced-before-submit", "session", "cancelled", "", "model", "provider", TokenUsage.empty());
        });
        stub().beforeAwait(() -> {
            try {
                firstStop.get().get(1, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new AssertionError("멈춘 run 을 두고 중지 요청이 실패로 답했다", ex);
            }
        });
        ChatTurn turn;
        try {
            turn = chat.send(dad, null, "run 을 보기 전에 멈춰 줘", "dad");
        } finally {
            executor.shutdownNow();
        }

        assertThat(stub().stopped()).containsExactly("run-raced-before-submit", "run-raced-before-submit");
        assertThat(turn.cancelled()).isTrue();
    }

    @Test
    @DisplayName("제출 전에 중지를 요청해도 등록된 run을 곧바로 멈춘다")
    void stoppingBeforeSubmitStopsRegisteredRunImmediately() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-race", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> stopRequest = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        try {
            stub().willAnswer(command -> {
                Long executionId = latestExecution(dad).id();
                stopRequest.set(executor.submit(() -> {
                    requested.countDown();
                    chat.stop(dad, executionId);
                }));
                try {
                    assertThat(requested.await(1, TimeUnit.SECONDS)).isTrue();
                    assertThat(awaitCancelled(executionId)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return HermesRunResult.of(
                        "run-race", "session", "cancelled", "", "model", "provider", TokenUsage.empty());
            });

            ChatTurn turn = chat.send(dad, null, "제출 전에 멈춰 줘", "dad");

            stopRequest.get().get(1, TimeUnit.SECONDS);
            assertThat(stub().stopped()).containsExactly("run-race");
            assertThat(executions.findById(turn.executionId()).orElseThrow().status())
                    .isEqualTo(ExecutionStatus.CANCELLED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Hermes 제출 전 중지는 run 없이 성공하고 취소로 끝난다")
    void stopBeforeHermesSubmitSucceedsWithoutRunAndEndsCancelled() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> stop = new AtomicReference<>();
        try {
            doAnswer(invocation -> {
                        invocation.callRealMethod();
                        Long executionId = invocation.getArgument(1);
                        stop.set(executor.submit(() -> chat.stop(dad, executionId)));
                        assertThat(awaitCancelled(executionId)).isTrue();
                        return null;
                    })
                    .when(turns)
                    .rekey(any(), any());

            ChatTurn turn = chat.send(dad, null, "제출 전 중지", "dad");

            stop.get().get(1, TimeUnit.SECONDS);
            assertThat(stub().received()).isEmpty();
            assertThat(turn.cancelled()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("완료를 저장한 뒤 닫기 전 중지는 실행이 끝났다고 응답한다")
    void stopAfterSavingCompletionBeforeCloseRespondsThatRunEnded() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-completed", "session", "completed", "완료", "model", "provider", TokenUsage.empty()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> stop = new AtomicReference<>();
        try {
            doAnswer(invocation -> {
                        invocation.callRealMethod();
                        stop.set(executor.submit(
                                () -> chat.stop(dad, latestExecution(dad).id())));
                        return null;
                    })
                    .when(turns)
                    .markFinished(any());

            chat.send(dad, null, "완료 경합", "dad");

            assertThatThrownBy(() -> stop.get().get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex.getCause()).code())
                            .isEqualTo(ErrorCode.EXECUTION_NOT_RUNNING));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("한 번에 받는 turn도 도는 동안 멈출 수 있다")
    void nonStreamTurnCanAlsoBeStoppedWhileRunning() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-sync", "session", "cancelled", "중단", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        ChatTurn turn = chat.send(dad, null, "멈춰 줘", "dad");

        assertThat(turn.cancelled()).isTrue();
        assertThat(stub().stopped()).containsExactly("run-sync");
    }

    @Test
    @DisplayName("같은 대화에서 도는 turn이 있으면 다음 turn을 거절한다")
    void rejectsNextTurnWhileTurnRunsInSameConversation() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-busy", "session", "completed", "완료", "model", "provider", TokenUsage.empty()));
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            awaiting.countDown();
            try {
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatTurn> first = executor.submit(() -> chat.send(dad, null, "첫 질문", "dad"));
            assertThat(awaiting.await(1, TimeUnit.SECONDS)).isTrue();
            Long conversationId = latestExecution(dad).conversationId();

            assertThatThrownBy(() -> chat.send(dad, conversationId, "두 번째 질문", "dad"))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.CONVERSATION_BUSY);

            release.countDown();
            first.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private boolean awaitCancelled(Long executionId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (turns.isCancelled(executionId)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
