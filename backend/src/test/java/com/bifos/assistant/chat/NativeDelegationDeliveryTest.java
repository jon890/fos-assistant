package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegationFinished;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.NativeResultFixtures;
import com.bifos.assistant.testsupport.PendingQueueFixtures;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hermes 내장 위임 결과로 부모 대화의 자동 turn 이 열리는 흐름을 본다(스펙 REQ10~REQ13, ADR-20261117 / native-delegation-wake).
 *
 * <p>끝 소식 경로와 6초 예약은 {@code SubagentSessionEndpointTest} 와 {@code SubagentStopRecorderTest} 가 본다. 여기서는 끝 소식이 적힌
 * 등록 줄을 넣고 예약이 낼 사건을 바로 낸다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class NativeDelegationDeliveryTest {
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String PROFILE = "native-wake";
    private static final String EMAIL = "native-wake@example.com";

    @Autowired
    ApplicationEventPublisher publisher;

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
    AgentExecutionRepository executions;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    PendingMessageStore pendingRows;

    @Autowired
    ChatService chatService;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private Agent chief;
    private Conversation conversation;
    private AgentExecution origin;
    private final List<ChatEvent> seen = new CopyOnWriteArrayList<>();
    private Runnable unsubscribe = () -> {
    };


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        stub().reset();
        NativeResultFixtures.clear(jdbc, PROFILE);
        users.findByEmail(EMAIL).ifPresent(user -> {
            jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)", user.id());
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", user.id());
            users.delete(user);
        });
        AppUser user = users.save(AppUser.of(EMAIL, "아빠", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        chief = agents.save(Agent.of(
            "native-wake", "비서", "native-wake", "http://agent-runtime.test/p/native-wake", CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        origin = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(chief.id())
            .profileName(PROFILE)
            .hermesRunId("run-origin")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now().minusSeconds(600))
            .build());
        stub().willReturn(HermesRunResult.of("auto", "session", "completed", "정리한 답", "model", "provider", TokenUsage.empty()));
        seen.clear();
        unsubscribe = hub.subscribe(conversation.id(), seen::add);
    }


    @AfterEach
    void tearDown() {
        unsubscribe.run();
        awaitIdle();
    }


    @Test
    @DisplayName("끝난 결과로 자동 turn 하나가 열려 입력에 결과 글이 외부 데이터로 들고 알림 줄과 그 줄 번호, 사건의 상태가 남는다")
    void stoppedResultOpensAutoTurn() {
        long binding = stopped("SUCCEEDED", "장보기 목록을 정리했다", "장보기 목록 정리");

        finished();
        awaitIdle();

        assertThat(history()).extracting(ChatMessage::role, ChatMessage::content)
            .containsExactly(
                tuple(MessageRole.SYSTEM, "하위 작업이 끝났어요 · 장보기 목록 정리"),
                tuple(MessageRole.ASSISTANT, "정리한 답"));
        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input())
            .contains("Hermes 하위 작업의 결과가 도착했다.")
            .contains("<external-data>\n장보기 목록을 정리했다\n</external-data>");
        Long noticeId = history().getFirst().id();
        assertThat(column(binding, "notice_message_id")).isEqualTo(noticeId);
        assertThat(column(binding, "result_delivered_at")).isNotNull();
        ChatEvent system = seen.stream().filter(event -> "system".equals(event.type())).findFirst().orElseThrow();
        assertThat(system.messageId()).isEqualTo(noticeId);
        assertThat(system.nativeResult()).isNotNull();
        assertThat(system.nativeResult().status()).isEqualTo("SUCCEEDED");
    }


    @Test
    @DisplayName("실패한 결과도 실패 알림 줄과 자동 turn 을 연다")
    void failedResultOpensAutoTurn() {
        stopped("FAILED", "권한이 없다", "메일 정리");

        finished();
        awaitIdle();

        assertThat(history().getFirst().content()).isEqualTo("하위 작업을 하지 못했어요 · 메일 정리");
        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).contains("상태: FAILED");
    }


    @Test
    @DisplayName("같은 turn 의 자식 둘이 끝나면 자동 turn 하나에 알림 줄 둘로 전한다")
    void twoChildrenShareOneAutoTurn() {
        stopped("SUCCEEDED", "첫 결과", "첫 일");
        stopped("SUCCEEDED", "둘째 결과", "둘째 일");

        finished();
        awaitIdle();

        assertThat(history()).extracting(ChatMessage::content)
            .containsExactly("하위 작업이 끝났어요 · 첫 일", "하위 작업이 끝났어요 · 둘째 일", "정리한 답");
        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).contains("첫 결과").contains("둘째 결과");
    }


    @Test
    @DisplayName("도는 turn 이 있는 동안 끝난 결과는 그 turn 이 닫힌 뒤 한 번에 전한다")
    void resultWaitsForRunningTurn() {
        TurnHandle running = turns.open(dad.id(), conversation.id());
        stopped("SUCCEEDED", "결과", "일");

        finished();
        assertThat(stub().received()).as("도는 turn 이 있는 동안").isEmpty();

        turns.close(running);
        awaitReceived(1);
        awaitIdle();
        finished();
        awaitIdle();

        assertThat(stub().received()).as("도는 turn 이 닫힌 뒤 자동 turn 하나, 다시 깨워도 더 열지 않는다").hasSize(1);
    }


    @Test
    @DisplayName("대기 글이 있으면 대기 글 turn 이 먼저 돌고 그 뒤 결과를 전한다")
    void queuedMessageGoesFirst() {
        PendingQueueFixtures.queue(pendingRows, conversation.id(), "대기 글", false, Instant.parse("2026-09-30T00:00:00Z"));
        stopped("SUCCEEDED", "결과", "일");

        finished();
        awaitReceived(2);
        awaitIdle();

        assertThat(stub().received().getFirst().input()).endsWith("대기 글");
        assertThat(stub().received().get(1).input()).contains("Hermes 하위 작업의 결과가 도착했다.");
    }


    @Test
    @DisplayName("끝 소식 뒤 대화 에이전트로 제출된 turn 이 있어도 결과를 자동 turn 하나로 한 번만 전한다")
    void laterTurnDoesNotSwallowResult() {
        Instant stoppedAt = Instant.now().minusSeconds(120);
        NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), true, stoppedAt.minusSeconds(60), stoppedAt, "SUCCEEDED", "늦은 결과", "일");
        executions.save(AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(chief.id())
            .profileName(PROFILE)
            .hermesRunId("run-later")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(stoppedAt.plusSeconds(40))
            .build());

        finished();
        awaitReceived(1);
        awaitIdle();
        finished();
        awaitIdle();

        assertThat(stub().received()).as("이름을 붙인 profile 은 결과 줄을 남기지 않아 그 turn 이 결과를 읽지 못했다").hasSize(1);
        assertThat(stub().received().getFirst().input()).contains("<external-data>\n늦은 결과\n</external-data>");
        assertThat(history()).extracting(ChatMessage::content).first().isEqualTo("하위 작업이 끝났어요 · 일");
    }


    @Test
    @DisplayName("자동 turn 수가 상한에 닿았으면 자동 turn 없이 한도 알림 줄만 남긴다")
    void limitReachedLeavesOnlyLimitNotice() {
        jdbc.update("UPDATE conversation SET auto_turn_count = 10 WHERE id = ?", conversation.id());
        stopped("SUCCEEDED", "결과", "일");

        finished();
        awaitIdle();

        assertThat(stub().received()).isEmpty();
        assertThat(history()).extracting(ChatMessage::content)
            .containsExactly("자동으로 이어 가는 횟수를 넘었어요. 이어서 하려면 메시지를 보내 주세요");
    }


    @Test
    @DisplayName("사람이 없는 실행에서 나온 결과로 연 자동 turn 은 사람이 없는 실행으로 적힌다")
    void unattendedOriginMakesUnattendedAutoTurn() {
        jdbc.update("UPDATE agent_execution SET unattended = TRUE WHERE id = ?", origin.id());
        stopped("SUCCEEDED", "결과", "일");

        finished();
        awaitIdle();

        Long autoTurn = history().getLast().executionId();
        assertThat(jdbc.queryForObject("SELECT unattended FROM agent_execution WHERE id = ?", Boolean.class, autoTurn)).isTrue();
    }


    @Test
    @DisplayName("중지한 답의 결과는 자동 turn 없이 다음 사용자 메시지 turn 입력에 한 번 들고 그다음 대기 글 turn 에는 다시 들지 않는다")
    void stoppedTurnResultGoesIntoNextUserTurnOnce() {
        jdbc.update("UPDATE agent_execution SET status = 'CANCELLED' WHERE id = ?", origin.id());
        long binding = stopped("SUCCEEDED", "장보기 목록을 정리했다", "장보기 목록 정리");

        finished();
        awaitIdle();
        assertThat(stub().received()).as("중지한 답의 결과로는 자동 turn 을 열지 않는다").isEmpty();

        chatService.send(dad, conversation.id(), "이어서 해 줘", null);
        awaitIdle();

        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input())
            .contains("[중지한 답이 맡긴 하위 작업의 결과]")
            .contains("<external-data>\n장보기 목록을 정리했다\n</external-data>")
            .endsWith("[사용자 메시지]\n이어서 해 줘");
        assertThat(stub().received().getFirst().input().split("장보기 목록을 정리했다", -1)).as("결과 글은 한 번만 든다").hasSize(2);
        assertThat(column(binding, "result_delivered_at")).isNotNull();

        PendingQueueFixtures.queue(pendingRows, conversation.id(), "다음 질문", false, Instant.parse("2026-09-30T00:00:00Z"));
        finished();
        awaitReceived(2);
        awaitIdle();

        assertThat(stub().received().get(1).input()).doesNotContain("장보기 목록을 정리했다").endsWith("다음 질문");
        assertThat(history()).extracting(ChatMessage::content).doesNotContain("하위 작업이 끝났어요 · 장보기 목록 정리");
    }


    @Test
    @DisplayName("대기 글로 여는 turn 도 중지한 답의 결과를 입력에 넣는다")
    void queuedTurnCarriesStoppedResult() {
        jdbc.update("UPDATE agent_execution SET status = 'CANCELLED' WHERE id = ?", origin.id());
        long binding = stopped("FAILED", "권한이 없다", "메일 정리");
        PendingQueueFixtures.queue(pendingRows, conversation.id(), "대기 글", false, Instant.parse("2026-09-30T00:00:00Z"));

        finished();
        awaitReceived(1);
        awaitIdle();

        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().input()).contains("상태: FAILED").contains("권한이 없다").endsWith("대기 글");
        assertThat(column(binding, "result_delivered_at")).isNotNull();
    }


    private long stopped(String status, String text, String goal) {
        Instant stoppedAt = Instant.now().minusSeconds(10);
        return NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), true, stoppedAt.minusSeconds(60), stoppedAt, status, text, goal);
    }


    private void finished() {
        publisher.publishEvent(new DelegationFinished(conversation.id(), null));
    }


    private List<ChatMessage> history() {
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }


    private Object column(long bindingId, String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM hermes_session_binding WHERE id = ?", Object.class, bindingId);
    }


    private void awaitReceived(int count) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (stub().received().size() < count) {
            if (System.nanoTime() > deadline) {
                fail("Hermes 가 %d 번 받지 못했다 received=%d", count, stub().received().size());
            }
            sleep();
        }
    }


    private void awaitIdle() {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (turns.markOf(conversation.id()).running()) {
            if (System.nanoTime() > deadline) {
                fail("대화의 turn 이 %s 안에 끝나지 않았다", WAIT_LIMIT);
            }
            sleep();
        }
    }


    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("기다리는 중에 끊겼다");
        }
    }
}
