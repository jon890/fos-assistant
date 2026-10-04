package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 먼저 살펴보기의 도구 호출 상한과 멈춘 뒤의 알림 줄, 대기 메시지를 본다. 시간 상한은 {@code max-duration} 을 짧게 둔
 * {@code ProactiveCheckTimeLimitTest} 가 본다.
 *
 * <p>Hermes 의 run 은 완료를 기다리는 자리에서 중지가 올 때까지 멈춰 둔다. 실제 Hermes 에서 도는 run 과 같다. 멈춘 run 은
 * {@code cancelled} 로 끝난다. 모든 데이터는 합성이다.
 */
@SpringBootTest(
        properties = {
            "hermes.run-timeout=30s",
            "assistant.proactive-check.max-duration=20s",
            "assistant.proactive-check.max-tool-calls=" + ProactiveCheckLimitTest.MAX_TOOL_CALLS,
            "assistant.memory.propose.enabled=false"
        })
@ActiveProfiles("test")
@Import(ProactiveCheckLimitTest.StubRuntime.class)
class ProactiveCheckLimitTest {

    static final int MAX_TOOL_CALLS = 3;

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String START_NOTICE = "먼저 살펴보기를 시작했어요";
    private static final String TOOL_LIMIT_NOTICE = "도구 호출 한도에 닿아 살펴보기를 멈췄어요";
    private static final String USER_STOP_NOTICE = "살펴보기를 멈췄어요";
    private static final String PENDING_TEXT = "살펴보는 동안 보낸 질문";
    private static final String PENDING_ANSWER = "대기 메시지에 답했어요";
    private static final String FAILED_NOTICE = "살펴보기를 끝내지 못했어요. 잠시 뒤 다시 눌러 주세요";

    /** 상한에 닿은 살펴보기에 멈추기를 부르는 최대 횟수다. 문서의 「상한」 이 정한다. */
    private static final int STOP_ATTEMPTS = 3;

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    ProactiveCheckService service;

    /** 멈추기가 실패하는 경우를 만든다. 정하지 않은 검사에서는 실제 그대로다. */
    @MockitoSpyBean
    ChatService chat;

    @Autowired
    TurnCancellation turns;

    @Autowired
    HermesRunsClient hermes;

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
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TransactionTemplate transactions;

    /** 실제 Hermes 를 부르지 않도록 켜진 toolset 을 대역으로 둔다. */
    @MockitoBean
    HermesToolsetClient toolsets;

    /** 켜진 스킬 목록을 대역으로 둔다. */
    @MockitoBean
    HermesSkillClient skillClient;

    /** 도구 사건을 흘리는 대역이다. 검사마다 흘릴 사건을 정한다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    private CurrentUser owner;
    private Agent agent;
    private Conversation conversation;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user =
                users.save(AppUser.of("limit-" + suffix + "@example.com", "한도", 1L, UserRole.MEMBER, Instant.now()));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "limit-" + suffix;
        agent = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now()));
        conversation = conversations.save(
                Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 커리어", agent.id(), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        awaitIdle(conversation.id());
        stub().reset();
        checks.deleteAll(checks.findAll().stream()
                .filter(check -> check.userId().equals(owner.id()))
                .toList());
        List<AgentExecution> ownExecutions = executions.findAll().stream()
                .filter(execution -> execution.userId().equals(owner.id()))
                .toList();
        executionEvents.deleteAll(executionEvents.findAll().stream()
                .filter(event -> ownExecutions.stream()
                        .anyMatch(execution -> execution.id().equals(event.executionId())))
                .toList());
        executions.deleteAll(ownExecutions);
        transactions.executeWithoutResult(status -> {
            pendingMessages.deleteAllOf(conversation.id());
            messages.deleteAll(messages.findByConversationIdOrderByIdAsc(conversation.id()));
            conversations.deleteById(conversation.id());
        });
        agents.deleteById(agent.id());
        users.deleteById(owner.id());
    }

    @Test
    @DisplayName("도구 호출이 max-tool-calls 를 넘으면 Hermes 에 중지가 가고 CHECK_TOOL_LIMIT 와 도구 호출 한도 알림 줄만 남는다")
    void stopsAtToolLimitAndLeavesOnlyToolLimitNotice() {
        holdUntilStopped();
        stub().willAnswer(command -> cancelled("<fos-check-result>{\"version\":1"));
        hermesStreams(toolEvents(MAX_TOOL_CALLS + 1));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TOOL_LIMIT");
        assertThat(check.toolCalls()).isEqualTo(MAX_TOOL_CALLS + 1);
        assertThat(check.finishedAt()).isNotNull();
        assertThat(stub().stopped()).as("살펴보기 turn 의 run 에 간 중지").contains(rootRunId(check));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .as("시작 줄과 멈춤 줄뿐이고 답 조각은 남지 않는다")
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.SYSTEM, START_NOTICE), tuple(MessageRole.SYSTEM, TOOL_LIMIT_NOTICE));
    }

    @Test
    @DisplayName("도구 호출이 max-tool-calls 와 같으면 멈추지 않고 SUCCEEDED 로 끝난다")
    void doesNotStopAtExactlyMaxToolCalls() {
        stub().willAnswer(command -> completed(block("{\"version\":1,\"outcome\":\"NOTHING_NEW\"}")));
        hermesStreams(toolEvents(MAX_TOOL_CALLS));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(check.errorCode()).isNull();
        assertThat(check.toolCalls()).isEqualTo(MAX_TOOL_CALLS);
        assertThat(stub().stopped()).as("Hermes 에 간 중지").isEmpty();
    }

    @Test
    @DisplayName("상한으로 멈춘 살펴보기는 대기 메시지를 멈추지 않고 그 메시지가 다음 turn 으로 나간다")
    void sendsPendingMessagesAfterLimitStop() {
        AtomicBoolean queued = new AtomicBoolean();
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> {
            // 살펴보기 turn 이 도는 동안 사용자가 대기 메시지를 보낸다.
            if (queued.compareAndSet(false, true)) {
                pendingMessages.save(
                        ChatPendingMessage.queued(conversation.id(), owner.id(), PENDING_TEXT, false, Instant.now()));
            }
            await(stopReceived);
        });
        stub().willAnswer(command ->
                command.input().contains(PENDING_TEXT) ? completed(PENDING_ANSWER) : cancelled("멈춘 답"));
        hermesStreams(toolEvents(MAX_TOOL_CALLS + 1));

        runCheck();
        awaitUntil(
                () -> !turns.markOf(conversation.id()).running()
                        && messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                                .anyMatch(message -> PENDING_ANSWER.equals(message.content())),
                "대기 메시지 turn 이 끝난다");

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TOOL_LIMIT");
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .as("보낸 대기 줄은 남지 않는다")
                .isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(
                        tuple(MessageRole.SYSTEM, START_NOTICE),
                        tuple(MessageRole.SYSTEM, TOOL_LIMIT_NOTICE),
                        tuple(MessageRole.USER, PENDING_TEXT),
                        tuple(MessageRole.ASSISTANT, PENDING_ANSWER));
    }

    @Test
    @DisplayName("사용자가 중지로 멈추면 오류 코드 없이 STOPPED 와 멈춤 알림 줄이 남고 대기 줄이 멈춘다")
    void holdsPendingMessagesWhenUserStops() throws InterruptedException {
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> {
            awaiting.countDown();
            await(stopReceived);
        });
        stub().willAnswer(command -> cancelled("멈춘 답"));

        service.start(owner, agent.code(), CheckTrigger.MANUAL);
        assertThat(awaiting.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
                .as("살펴보기 turn 이 완료 대기에 들어섰다")
                .isTrue();
        pendingMessages.save(
                ChatPendingMessage.queued(conversation.id(), owner.id(), PENDING_TEXT, false, Instant.now()));
        // POST /api/v1/executions/{executionId}/stop 이 부르는 자리다.
        chat.stop(owner, turns.markOf(conversation.id()).executionId());
        awaitIdle(conversation.id());

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).as("사용자가 멈춘 살펴보기의 오류 코드").isNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly(START_NOTICE, USER_STOP_NOTICE);
        assertThat(pendingMessages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatPendingMessage::content, ChatPendingMessage::held)
                .containsExactly(tuple(PENDING_TEXT, true));
        assertThat(stub().received())
                .as("대기 메시지로 연 turn")
                .noneMatch(command -> command.input().contains(PENDING_TEXT));
    }

    @Test
    @DisplayName("상한으로 멈춘 뒤 완료 대기가 ApiException 으로 끝나도 알림 줄은 시작 줄과 멈춤 줄 둘이다")
    void leavesOnlyStopNoticeWhenAwaitFailsAfterLimitStop() {
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> {
            // 제출은 이미 끝났다. 중지를 받은 뒤의 완료 대기만 던진다.
            stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "runtime went away"));
            stopReceived.countDown();
        });
        stub().beforeAwait(() -> await(stopReceived));
        stub().willAnswer(command -> cancelled("멈춘 답"));
        hermesStreams(toolEvents(MAX_TOOL_CALLS + 1));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TOOL_LIMIT");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly(START_NOTICE, TOOL_LIMIT_NOTICE);
    }

    @Test
    @DisplayName("상한 중지가 한 번 HERMES_UNAVAILABLE 로 실패하면 다시 멈춰 CHECK_TOOL_LIMIT 와 도구 호출 한도 알림 줄로 끝난다")
    void retriesLimitStopAfterFailedStop() {
        holdUntilStopped();
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not stop every Hermes run"))
                .doCallRealMethod()
                .when(chat)
                .stop(any(), any());
        stub().willAnswer(command -> cancelled("<fos-check-result>{\"version\":1"));
        hermesStreams(toolEvents(MAX_TOOL_CALLS + 1));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TOOL_LIMIT");
        assertThat(stub().stopped()).as("다시 시도해 살펴보기 turn 의 run 에 간 중지").containsExactly(rootRunId(check));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly(START_NOTICE, TOOL_LIMIT_NOTICE);
    }

    @Test
    @DisplayName("상한 중지가 계속 실패한 뒤 turn 이 예외로 끝나면 상한 까닭 없이 FAILED 와 그 오류 코드와 실패 알림 줄이 남는다")
    void recordsFailureWhenLimitStopKeepsFailingAndTurnThrows() {
        CountDownLatch attempts = new CountDownLatch(STOP_ATTEMPTS);
        doAnswer(invocation -> {
                    attempts.countDown();
                    throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not stop every Hermes run");
                })
                .when(chat)
                .stop(any(), any());
        stub().beforeAwait(() -> {
            // 멈추기 시도를 모두 쓴 뒤 turn 이 실패한다. 마지막 시도가 까닭을 되돌릴 틈을 둔다.
            await(attempts);
            pause(Duration.ofMillis(300));
            stub().willFail(new ApiException(ErrorCode.HERMES_RUN_FAILED, "run failed"));
        });
        stub().willAnswer(command -> cancelled("멈추지 않은 답"));
        hermesStreams(toolEvents(MAX_TOOL_CALLS + 1));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(attempts.getCount()).as("남은 멈추기 시도").isZero();
        assertThat(check.status()).isEqualTo(CheckStatus.FAILED);
        assertThat(check.errorCode()).as("상한 까닭이 아니라 turn 의 오류 코드").isEqualTo("HERMES_RUN_FAILED");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly(START_NOTICE, FAILED_NOTICE);
    }

    /** 살펴보기를 시작하고 그 대화의 잠금이 풀릴 때까지 기다린다. */
    private void runCheck() {
        service.start(owner, agent.code(), CheckTrigger.MANUAL);
        awaitIdle(conversation.id());
    }

    private ProactiveCheck onlyCheck() {
        List<ProactiveCheck> found = checks.findAll().stream()
                .filter(check -> check.conversationId().equals(conversation.id()))
                .toList();
        assertThat(found).as("점검 대화의 살펴보기 줄").hasSize(1);
        return found.getFirst();
    }

    private String rootRunId(ProactiveCheck check) {
        return executions.findById(check.rootExecutionId()).orElseThrow().hermesRunId();
    }

    /** 완료를 기다리는 자리에서 중지가 올 때까지 멈춰 둔다. 오지 않아도 10초 뒤에는 이어진다. */
    private void holdUntilStopped() {
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> await(stopReceived));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static RunEvent[] toolEvents(int count) {
        List<RunEvent> events = new ArrayList<>();
        events.add(new RunEvent("message.delta", "<fos-check-result>{\"version\"", null, null, null, null));
        for (int i = 0; i < count; i++) {
            events.add(new RunEvent("tool.started", null, "web_search", "query-" + i, null, null));
        }
        return events.toArray(RunEvent[]::new);
    }

    private static String block(String json) {
        return "블록 밖의 글\n<fos-check-result>\n" + json + "\n</fos-check-result>";
    }

    private static HermesRunResult completed(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }

    /** 중지를 받은 Hermes run 의 끝이다. 그때까지의 답을 싣는다. */
    private static HermesRunResult cancelled(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "cancelled", output, "model", "provider", TokenUsage.empty());
    }

    /** Hermes 가 스트림으로 이 사건들을 차례로 보낸 것처럼 만든다. */
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

    private void awaitIdle(Long conversationId) {
        awaitUntil(() -> !turns.markOf(conversationId).running(), "대화 " + conversationId + " 의 turn 이 끝난다");
    }

    /** 조건이 참이 될 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private static void awaitUntil(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("%s 안에 조건을 만족하지 못했다: %s", WAIT_LIMIT, description);
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
