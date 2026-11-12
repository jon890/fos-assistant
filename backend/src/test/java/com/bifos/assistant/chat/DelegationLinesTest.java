package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.delivery.domain.ResultDelivery;
import com.bifos.assistant.chat.delivery.infra.ResultDeliveryRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegationFinished;
import com.bifos.assistant.chat.nextturn.application.dto.DelegationStarted;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 대화 안의 맡긴 줄과 결과 도착 줄, 승인 카드의 요청 에이전트를 본다(docs/features/agent-team/spec.md 의 REQ3, REQ4).
 *
 * <p>결과 도착 줄은 실제 자동 turn 이 남긴 알림 줄과 묶음으로 본다. 자동 turn 은 가상 스레드에서 돌아 끝날 때까지 기다린다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class DelegationLinesTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    @Autowired
    ChatService chat;

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
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ResultDeliveryRepository deliveries;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    HermesRunsClient hermes;

    private CurrentUser dad;
    private Agent chief;
    private Agent shopper;
    private Conversation conversation;
    private AgentExecution root;
    private final List<Runnable> cleanups = new ArrayList<>();


    @BeforeEach
    void setUp() {
        awaitIdle();
        ((StubHermesRunsClient)hermes).reset();
        ((StubHermesRunsClient)hermes).willReturn(
            HermesRunResult.of("auto", "session", "completed", "정리한 답", "model", "provider", TokenUsage.empty()));
        deliveries.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        users.deleteAll();

        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        chief = agents.save(agent("dad", "비서", user.id()));
        shopper = agents.save(agent("shopper", "쇼핑 담당", user.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        root = executions.save(execution(null, chief, ExecutionStatus.SUCCEEDED, null));
    }


    @AfterEach
    void tearDown() {
        awaitIdle();
        cleanups.reversed().forEach(Runnable::run);
        cleanups.clear();
    }


    @Test
    @DisplayName("답 줄에는 그 turn 이 바로 맡긴 위임만 이름표와 상태로 싣고 손자와 위임이 아닌 자식은 뺀다")
    void answerCarriesOnlyDirectDelegations() {
        AgentExecution running = executions.save(execution(root, shopper, ExecutionStatus.RUNNING, "key-1"));
        AgentExecution failed = executions.save(execution(root, chief, ExecutionStatus.FAILED, "key-2"));
        executions.save(execution(running, chief, ExecutionStatus.RUNNING, "key-3"));
        executions.save(execution(root, chief, ExecutionStatus.SUCCEEDED, null));
        ChatMessage answer = messages.save(ChatMessage.fromAssistant(conversation.id(), "맡겼어요", root.id(), Instant.now()));

        Map<Long, List<DelegationLine>> lines = chat.delegationsByAnswer(List.of(answer));

        assertThat(lines.get(root.id()))
            .containsExactly(
                new DelegationLine(running.id(), root.id(), ExecutionStatus.RUNNING, label(shopper)),
                new DelegationLine(failed.id(), root.id(), ExecutionStatus.FAILED, label(chief)));
        assertThat(lines).as("손자의 부모는 답 줄이 아니라 빠진다").containsOnlyKeys(root.id());
    }


    @Test
    @DisplayName("지운 에이전트에 맡긴 줄은 이름표의 네 칸이 비어 있다")
    void deletedAgentHasEmptyLabel() {
        AgentExecution done = executions.save(execution(root, shopper, ExecutionStatus.SUCCEEDED, "key-1"));
        shopper.markDeleted(Instant.now());
        agents.save(shopper);
        ChatMessage answer = messages.save(ChatMessage.fromAssistant(conversation.id(), "맡겼어요", root.id(), Instant.now()));

        assertThat(chat.delegationsByAnswer(List.of(answer)).get(root.id()))
            .containsExactly(new DelegationLine(done.id(), root.id(), ExecutionStatus.SUCCEEDED, AgentLabel.DELETED));
    }


    @Test
    @DisplayName("위임 결과 알림 줄에 그 줄이 전한 결과가 붙고 그 아래 답은 대화의 에이전트가 쓴다")
    void resultNoticeCarriesDeliveredResults() {
        AgentExecution done = executions.save(execution(root, shopper, ExecutionStatus.SUCCEEDED, "key-1"));
        AgentExecution failed = executions.save(execution(root, chief, ExecutionStatus.FAILED, "key-2"));
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        cleanups.add(hub.subscribe(conversation.id(), received::add));

        publisher.publishEvent(new DelegationFinished(conversation.id(), failed.id()));
        awaitIdle();

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history).extracting(ChatMessage::role).containsExactly(MessageRole.SYSTEM, MessageRole.ASSISTANT);
        ChatMessage notice = history.getFirst();
        ResultDelivery delivery = deliveries.findByConversationId(conversation.id()).getFirst();
        assertThat(delivery.delegationNoticeMessageId()).isEqualTo(notice.id());

        Map<Long, List<DelegationLine>> results = chat.deliveredResults(dad, conversation.id());
        assertThat(results).containsOnlyKeys(notice.id());
        assertThat(results.get(notice.id()))
            .extracting(DelegationLine::executionId, DelegationLine::status, line -> line.agent().name())
            .containsExactly(
                tuple(done.id(), ExecutionStatus.SUCCEEDED, "쇼핑 담당"),
                tuple(failed.id(), ExecutionStatus.FAILED, "비서"));
        assertThat(received)
            .as("자동 turn 이 도는 동안 그리는 알림 줄 사건도 같은 결과를 싣는다")
            .filteredOn(event -> "system".equals(event.type()))
            .singleElement()
            .satisfies(event -> assertThat(event.deliveredResults()).isEqualTo(results.get(notice.id())));
        assertThat(executions.findById(history.getLast().executionId()).orElseThrow().agentId())
            .as("결과로 다시 쓴 답은 대화의 에이전트가 쓴다")
            .isEqualTo(chief.id());
    }


    @Test
    @DisplayName("남의 실행 번호가 묶음에 들었어도 결과 도착 줄에 싣지 않는다")
    void resultNoticeSkipsOtherUsersExecutions() {
        AppUser other = users.save(AppUser.of("mom@example.com", "mom", 1L, UserRole.MEMBER));
        AgentExecution foreign = executions.save(AgentExecution.builder()
            .userId(other.id())
            .conversationId(conversation.id())
            .agentId(shopper.id())
            .profileName("shopper")
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now())
            .build());
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "알림", Instant.now()));
        deliveries.save(ResultDelivery.opened(
            conversation.id(),
            "{\"version\":1,\"items\":[{\"source\":\"DELEGATION\",\"resultKey\":\"" + foreign.id() + "\"}]}",
            notice.id(),
            notice.id(),
            Instant.now()));

        assertThat(chat.deliveredResults(dad, conversation.id())).isEmpty();
    }


    @Test
    @DisplayName("turn 이 바로 맡긴 위임이 시작하고 끝나면 대화 단위 구독에 맡긴 줄 사건이 가고 손자는 가지 않는다")
    void publishesDelegationEventsForDirectDelegationsOnly() {
        List<ChatEvent> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(conversation.id(), received::add);
        cleanups.add(unsubscribe);
        AgentExecution child = executions.save(execution(root, shopper, ExecutionStatus.RUNNING, "key-1"));
        AgentExecution grandchild = executions.save(execution(child, chief, ExecutionStatus.RUNNING, "key-2"));

        publisher.publishEvent(new DelegationStarted(conversation.id(), child.id()));
        publisher.publishEvent(new DelegationStarted(conversation.id(), grandchild.id()));

        assertThat(received)
            .filteredOn(event -> "delegation".equals(event.type()))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.conversationId()).isEqualTo(conversation.publicId());
                assertThat(event.executionId()).isEqualTo(child.id());
                assertThat(event.delegation())
                    .isEqualTo(new DelegationLine(child.id(), root.id(), ExecutionStatus.RUNNING, label(shopper)));
            });
    }


    @Test
    @DisplayName("승인 카드에는 대화의 에이전트와 다른 에이전트가 요청한 줄에만 그 이름표를 붙인다")
    void approvalCarriesRequesterOnlyWhenOtherAgent() {
        ConnectorConnection connection = connections.save(ConnectorConnection.pending(dad.id(), "demo-mail", Instant.now()));
        cleanups.add(() -> connections.deleteById(connection.id()));
        ConnectorAction byChild = saveAction(connection, shopper);
        ConnectorAction byChief = saveAction(connection, chief);

        Map<UUID, AgentLabel> requesters = actionService.requestersOf(
            dad, conversation.id(), List.of(byChild.publicId(), byChief.publicId()));

        assertThat(requesters).containsOnlyKeys(byChild.publicId());
        assertThat(requesters.get(byChild.publicId())).isEqualTo(label(shopper));
    }


    private ConnectorAction saveAction(ConnectorConnection connection, Agent agent) {
        ConnectorAction action = actions.save(ConnectorAction.decided(
            connection,
            agent.id(),
            root,
            "mcp__mail__send",
            "send",
            new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.ALWAYS),
            false,
            "delegation-lines-" + UUID.randomUUID(),
            "synthetic-hash",
            Instant.now()));
        cleanups.add(() -> actions.deleteById(action.id()));
        return action;
    }


    private static AgentLabel label(Agent agent) {
        return new AgentLabel(agent.code(), agent.name(), agent.icon(), null);
    }


    /** {@code parent} 가 null 이면 루트 실행이다. {@code delegationKey} 가 null 이면 위임이 아닌 자식이다. */
    private AgentExecution execution(AgentExecution parent, Agent agent, ExecutionStatus status, String delegationKey) {
        Instant now = Instant.now();
        AgentExecution.Builder builder = AgentExecution.builder()
            .userId(dad.id())
            .conversationId(conversation.id())
            .agentId(agent.id())
            .parentExecutionId(parent == null ? null : parent.id())
            .rootExecutionId(parent == null ? null : parent.treeRootId())
            .delegationKey(delegationKey)
            .profileName(agent.code())
            .costMode(CostMode.SUBSCRIPTION)
            .status(status)
            .startedAt(now.minusSeconds(30));
        if (status != ExecutionStatus.RUNNING) {
            builder.timing(now.minusSeconds(30), now);
        }
        AgentExecution execution = builder.build();
        execution.recordOutput("결과");
        return execution;
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


    private void awaitIdle() {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        for (Conversation it : conversations.findAll()) {
            while (turns.markOf(it.id()).running()) {
                if (System.nanoTime() > deadline) {
                    fail("대화 %d 의 turn 이 %s 안에 끝나지 않았다", it.id(), WAIT_LIMIT);
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
}
