package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.TurnCancellation;
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
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatStopTest {

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ProviderStateRepository providerStates;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired MemoryRepository memories;
    @Autowired HermesRunsClient hermes;
    @Autowired TurnCancellation turns;

    @MockitoBean HermesRunEventStream eventStream;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of(
                profileName, profileName, profileName, "http://agent-runtime.test/p/" + profileName,
                "anthropic", "example-model-large", CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private void hermesStreams(RunEvent... events) {
        doAnswer(invocation -> {
            Consumer<RunEvent> onEvent = invocation.getArgument(3);
            for (RunEvent event : events) {
                onEvent.accept(event);
            }
            return null;
        }).when(eventStream).open(any(), any(), any(), any(), any());
    }

    private AgentExecution latestExecution(CurrentUser user) {
        return executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 1)).getFirst();
    }

    private List<ExecutionEventType> eventTypes(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId))
                .stream().map(ExecutionEvent::eventType).toList();
    }

    @BeforeEach
    void reset() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        modelOptions.deleteAll();
        providerStates.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
    }

    @Test
    void 도는_turn을_멈추면_취소_상태와_Hermes_결과를_남긴다() {
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
        assertThat(messages.findByConversationIdOrderByIdAsc(stopped.conversationId())).last()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
                    assertThat(message.content()).isEqualTo("절반");
                });
        assertThat(eventTypes(stopped.executionId())).contains(ExecutionEventType.RUN_CANCELLED);
    }

    @Test
    void 중지한_결과의_output이_비면_스트림_조각을_답으로_남긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        hermesStreams(new RunEvent("message.delta", "앞부분", null, null, null, null));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "멈춰 줘", "dad", relayed::add);

        assertThat(messages.findByConversationIdOrderByIdAsc(relayed.getLast().conversationId())).last()
                .satisfies(message -> assertThat(message.content()).isEqualTo("앞부분"));
    }

    @Test
    void 중지한_결과와_스트림_조각이_모두_비면_답_메시지를_만들지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-stop", "session", "cancelled", "", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "멈춰 줘", "dad", relayed::add);

        ChatEvent stopped = relayed.getLast();
        assertThat(stopped.type()).isEqualTo("stopped");
        assertThat(stopped.messageId()).isNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(stopped.conversationId()))
                .singleElement().satisfies(message -> assertThat(message.role()).isEqualTo(MessageRole.USER));
    }

    @Test
    void 다른_사용자의_도는_실행은_찾을_수_없다고_응답한다() {
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

        chat.send(dad, null, "멈춰 줘", "dad");
    }

    @Test
    void 끝난_실행을_멈추면_실행이_끝났다고_응답한다() {
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
    void 중지_전송이_실패하면_다음_요청이_같은_run에_다시_보낸다() {
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
    void 중지_요청을_두_번_받아도_성공한_run에는_한_번만_보낸다() {
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
    void 제출_전에_중지_전송이_실패해도_다음_중지_요청이_같은_run에_다시_보낸다() throws Exception {
        CurrentUser dad = member("dad@example.com", "dad");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> firstStop = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        stub().onStop(runId -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "temporary failure");
            }
        });
        stub().willAnswer(command -> {
            Long executionId = latestExecution(dad).id();
            firstStop.set(executor.submit(() -> {
                requested.countDown();
                chat.stop(dad, executionId);
            }));
            return HermesRunResult.of(
                    "run-before-submit-retry", "session", "cancelled", "", "model", "provider", TokenUsage.empty());
        });
        stub().beforeAwait(() -> {
            try {
                assertThat(requested.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> firstStop.get().get(1, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ApiException.class)
                        .satisfies(ex -> assertThat(((ApiException) ex.getCause()).code())
                                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
                chat.stop(dad, latestExecution(dad).id());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        try {
            chat.send(dad, null, "제출 전에 실패해도 멈춰 줘", "dad");
        } finally {
            executor.shutdownNow();
        }

        assertThat(stub().stopped()).containsExactly("run-before-submit-retry", "run-before-submit-retry");
    }

    @Test
    void provider_전환_뒤에는_새_실행만_중지_대상이고_이전_실행은_끝난_것으로_응답한다() {
        CurrentUser dad = member("dad@example.com", "dad");
        Agent agent = agents.findByCode("dad").orElseThrow();
        modelSelector.replace(agent, List.of(
                new ModelOption("anthropic", "example-model-large"),
                new ModelOption("nvidia", "example-model-small")));
        stub().willReturnInOrder(
                new HermesRunResult("run-blocked", "session", "failed", "", null, null,
                        HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account", TokenUsage.empty()),
                HermesRunResult.of("run-next", "session", "cancelled", "답", "model", "provider", TokenUsage.empty()));

        List<ChatEvent> relayed = new ArrayList<>();
        stub().beforeAwait(() -> {
            if (stub().received().size() != 2) return;
            List<ChatEvent> startedEvents = relayed.stream()
                    .filter(event -> event.type().equals("started")).toList();
            assertThat(startedEvents).hasSize(2);
            assertThatThrownBy(() -> chat.stop(dad, startedEvents.getFirst().executionId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.EXECUTION_NOT_RUNNING);
            chat.stop(dad, startedEvents.get(1).executionId());
        });
        chat.stream(dad, null, "다음 provider 로 넘어가 줘", "dad", relayed::add);

        List<ChatEvent> started = relayed.stream().filter(event -> event.type().equals("started")).toList();
        assertThat(relayed).extracting(ChatEvent::type)
                .containsSequence("started", "reset", "started", "switched", "stopped");
        assertThat(started).hasSize(2);
        assertThat(started.getFirst().executionId()).isNotEqualTo(started.get(1).executionId());
        assertThat(relayed.getLast().executionId()).isEqualTo(started.get(1).executionId());
        assertThat(stub().stopped()).containsExactly("run-next");
    }

    @Test
    void 제출_전에_중지를_요청해도_등록된_run을_곧바로_멈춘다() throws Exception {
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
    void 한_번에_받는_turn도_도는_동안_멈출_수_있다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of(
                "run-sync", "session", "cancelled", "중단", "model", "provider", TokenUsage.empty()));
        stub().beforeAwait(() -> chat.stop(dad, latestExecution(dad).id()));

        ChatTurn turn = chat.send(dad, null, "멈춰 줘", "dad");

        assertThat(turn.cancelled()).isTrue();
        assertThat(stub().stopped()).containsExactly("run-sync");
    }

    @Test
    void 같은_대화에서_도는_turn이_있으면_다음_turn을_거절한다() throws Exception {
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
            if (turns.isCancelled(executionId)) return true;
            Thread.onSpinWait();
        }
        return false;
    }
}
