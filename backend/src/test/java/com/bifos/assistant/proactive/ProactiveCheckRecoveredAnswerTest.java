package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.RecoveredRunRecorder;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버가 다시 뜬 뒤 기동 정리가 다시 붙어 끝낸 점검 대화의 turn 이 무엇을 남기는지 본다.
 *
 * <p>실행 줄은 이전 프로세스가 남긴 것처럼 저장소로 직접 만들고, Hermes 의 답은 그대로 넘긴다. 실제로 돈 모델을 답에 실어 Hermes
 * 세션 조회를 부르지 않는다. 모든 데이터는 합성이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProactiveCheckRecoveredAnswerTest {

    private static final Instant STARTED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String FAILED_NOTICE = "살펴보기를 끝내지 못했어요. 잠시 뒤 다시 눌러 주세요";
    private static final String STOPPED_NOTICE = "살펴보기를 멈췄어요";
    private static final String CHECK_ANSWER = "살펴본 글\n<fos-check-result>\n"
            + "{\"version\":1,\"outcome\":\"NOTHING_NEW\",\"summary\":\"검사하지 않은 요약\"}\n</fos-check-result>";

    @Autowired
    RecoveredRunRecorder recorder;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    TransactionTemplate transactions;

    private final List<Long> createdChecks = new ArrayList<>();
    private final List<Long> createdExecutions = new ArrayList<>();

    private AppUser user;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = users.save(AppUser.of("recovered-" + suffix + "@example.com", "복구", 1L, UserRole.MEMBER, STARTED));
        agent = agents.save(Agent.of(
                "recovered-" + suffix,
                "커리어",
                "recovered-" + suffix,
                "http://agent-runtime.test/p/recovered-" + suffix,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                STARTED));
        conversation =
                conversations.save(Conversation.startedForCheck(user.id(), "먼저 살펴보기 · 커리어", agent.id(), STARTED));
    }

    @AfterEach
    void tearDown() {
        checks.deleteAllById(createdChecks);
        createdExecutions.forEach(id -> executionEvents.deleteAll(
                executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(id))));
        executions.deleteAllById(createdExecutions);
        transactions.executeWithoutResult(status -> {
            messages.deleteAll(messages.findByConversationIdOrderByIdAsc(conversation.id()));
            conversations.deleteById(conversation.id());
        });
        agents.deleteById(agent.id());
        users.deleteById(user.id());
    }

    @Test
    @DisplayName("기동 정리가 끝낸 살펴보기 turn 의 답은 남지 않고 실패 알림 줄 하나만 남는다")
    void leavesOnlyFailedNoticeForRecoveredCheckTurn() {
        AgentExecution root = checkRoot();

        boolean written = recorder.settle(root.id(), ended("completed", CHECK_ANSWER));

        assertThat(written).as("RUNNING 줄을 적었다").isTrue();
        assertThat(executions.findById(root.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.SYSTEM, FAILED_NOTICE));
        assertThat(history)
                .extracting(ChatMessage::content)
                .noneMatch(content -> content.contains("fos-check-result") || content.contains("검사하지 않은 요약"));
    }

    @Test
    @DisplayName("기동 정리가 끝낸 살펴보기 turn 이 취소로 끝났으면 그때까지의 답 대신 멈춤 알림 줄 하나만 남는다")
    void leavesOnlyStoppedNoticeForCancelledCheckTurn() {
        AgentExecution root = checkRoot();

        boolean written = recorder.settle(root.id(), ended("cancelled", CHECK_ANSWER));

        assertThat(written).as("RUNNING 줄을 적었다").isTrue();
        assertThat(executions.findById(root.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.SYSTEM, STOPPED_NOTICE));
    }

    @Test
    @DisplayName("취소로 끝난 살펴보기 turn 의 답이 비었어도 멈춤 알림 줄 하나는 남는다")
    void leavesStoppedNoticeForCancelledCheckTurnWithoutAnswer() {
        AgentExecution root = checkRoot();

        recorder.settle(root.id(), ended("cancelled", ""));

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content)
                .containsExactly(tuple(MessageRole.SYSTEM, STOPPED_NOTICE));
    }

    @Test
    @DisplayName("같은 점검 대화에서 사용자가 직접 보낸 보통 turn 의 복구 답은 그대로 남는다")
    void keepsRecoveredAnswerOfOrdinaryTurnInCheckConversation() {
        AgentExecution checkRoot = turn();
        ProactiveCheck check =
                ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, STARTED);
        check.attachRoot(checkRoot.id(), checkRoot.hermesSessionId());
        check.fail("INTERRUPTED", 0, 0, STARTED.plusSeconds(60));
        createdChecks.add(checks.save(check).id());
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), "이 공고를 더 알려 줘", STARTED.plusSeconds(120)));
        AgentExecution ordinary = turn();

        boolean written = recorder.settle(ordinary.id(), ended("completed", "공고의 요구 조건은 이렇다"));

        assertThat(written).as("RUNNING 줄을 적었다").isTrue();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role, ChatMessage::content, ChatMessage::executionId)
                .containsExactly(
                        tuple(MessageRole.USER, "이 공고를 더 알려 줘", null),
                        tuple(MessageRole.ASSISTANT, "공고의 요구 조건은 이렇다", ordinary.id()));
    }

    /** 점검 대화에서 돌던 살펴보기 turn 의 실행 줄이다. 살펴보기 줄이 그 실행을 루트로 가리킨다. */
    private AgentExecution checkRoot() {
        AgentExecution root = turn();
        ProactiveCheck check =
                ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, STARTED);
        check.attachRoot(root.id(), root.hermesSessionId());
        createdChecks.add(checks.save(check).id());
        return root;
    }

    /** 점검 대화에서 돌던 대화 turn 의 실행 줄이다. */
    private AgentExecution turn() {
        AgentExecution saved = executions.save(AgentExecution.builder()
                .userId(user.id())
                .agentId(agent.id())
                .conversationId(conversation.id())
                .profileName(agent.hermesProfile())
                .hermesSessionId("fos-" + UUID.randomUUID())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(STARTED)
                .build());
        createdExecutions.add(saved.id());
        return saved;
    }

    private static HermesRunResult ended(String status, String output) {
        return new HermesRunResult(
                "run-1",
                "sess-1",
                status,
                output,
                null,
                null,
                null,
                new TokenUsage(10L, 0L, 5L, 15L),
                new SessionRuntime("example-model", "example-provider"));
    }
}
