package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * 먼저 살펴보기의 시간 상한을 본다. {@code max-duration} 을 1초로 짧게 두고 {@code hermes.run-timeout} 은 넉넉히 둔다. 도구 호출
 * 상한과 멈춘 뒤의 대기 메시지는 {@code ProactiveCheckLimitTest} 가 본다.
 *
 * <p>모든 데이터는 합성이다.
 */
@SpringBootTest(
        properties = {
            "hermes.run-timeout=30s",
            "assistant.proactive-check.max-duration=" + ProactiveCheckTimeLimitTest.MAX_DURATION_MILLIS + "ms"
        })
@ActiveProfiles("test")
@Import(ProactiveCheckTimeLimitTest.StubRuntime.class)
class ProactiveCheckTimeLimitTest {

    static final long MAX_DURATION_MILLIS = 1000;

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

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
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TransactionTemplate transactions;

    /** 시간 상한 스레드가 멈추기를 불렀는지 본다. 그 밖의 동작은 실제 그대로다. */
    @MockitoSpyBean
    ChatService chat;

    /** 실제 Hermes 를 부르지 않도록 켜진 toolset 을 대역으로 둔다. */
    @MockitoBean
    HermesToolsetClient toolsets;

    /** 켜진 스킬 목록을 대역으로 둔다. */
    @MockitoBean
    HermesSkillClient skillClient;

    /** 실제 스트림 주소로 연결하지 않게 대역으로 둔다. 사건은 흘리지 않는다. */
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
                users.save(AppUser.of("time-" + suffix + "@example.com", "시간", 1L, UserRole.MEMBER, Instant.now()));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "time-" + suffix;
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
            messages.deleteAll(messages.findByConversationIdOrderByIdAsc(conversation.id()));
            conversations.deleteById(conversation.id());
        });
        agents.deleteById(agent.id());
        users.deleteById(owner.id());
    }

    @Test
    @DisplayName("max-duration 이 지나도 끝나지 않으면 Hermes 에 중지가 가고 CHECK_TIME_LIMIT 와 시간 한도 알림 줄이 남는다")
    void stopsAtTimeLimitAndLeavesTimeLimitNotice() {
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> await(stopReceived));
        stub().willAnswer(command -> result("cancelled", "<fos-check-result>{\"version\":1"));

        runCheck();

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.STOPPED);
        assertThat(check.errorCode()).isEqualTo("CHECK_TIME_LIMIT");
        assertThat(stub().stopped())
                .as("살펴보기 turn 의 run 에 간 중지")
                .contains(executions
                        .findById(check.rootExecutionId())
                        .orElseThrow()
                        .hermesRunId());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly("먼저 살펴보기를 시작했어요", "시간 한도에 닿아 살펴보기를 멈췄어요");
    }

    @Test
    @DisplayName("max-duration 안에 끝나면 그 시간이 지나도 시간 상한 스레드가 멈추기를 부르지 않는다")
    void doesNotStopAfterFinishingWithinTimeLimit() throws InterruptedException {
        stub().willAnswer(command -> result(
                "completed", "<fos-check-result>\n{\"version\":1,\"outcome\":\"NOTHING_NEW\"}\n</fos-check-result>"));

        runCheck();
        // 시간 상한 스레드가 깼을 시각을 넘겨 기다린다.
        Thread.sleep(MAX_DURATION_MILLIS + 500);

        ProactiveCheck check = onlyCheck();
        assertThat(check.status()).isEqualTo(CheckStatus.SUCCEEDED);
        assertThat(check.errorCode()).isNull();
        verify(chat, never()).stop(any(), any());
        assertThat(stub().stopped()).as("Hermes 에 간 중지").isEmpty();
    }

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

    private static HermesRunResult result(String status, String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, status, output, "model", "provider", TokenUsage.empty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
