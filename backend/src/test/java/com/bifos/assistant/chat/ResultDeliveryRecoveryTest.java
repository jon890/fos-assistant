package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.NextTurnDispatcher;
import com.bifos.assistant.chat.application.RecoveredRunRecorder;
import com.bifos.assistant.chat.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.application.ResultDeliveryRecovery;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.ResultDeliveryAttempt;
import com.bifos.assistant.chat.domain.ResultDeliveryItem;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 이전 프로세스가 {@code RUNNING} 으로 남긴 전달 시도를 기동할 때 닫고, 기동 정리가 정한 부모 실행 줄의 시도를 그 줄과 함께
 * 닫는지 본다(ADR-075).
 *
 * <p>줄은 이전 프로세스가 남긴 것처럼 저장소로 직접 만든다. 기준 시각은 고정한 값을 넘긴다. 깨우기를 켜 두어 「다시 열리지
 * 않는다」 검사가 실제로 기동 훑기를 거친다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class ResultDeliveryRecoveryTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    /** 기동 시각으로 쓰는 기준이다. 이보다 앞에 시작한 시도만 이전 프로세스의 것이다. */
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant BEFORE_START = STARTED_AT.minus(Duration.ofMinutes(1));
    private static final Instant AFTER_START = STARTED_AT.plus(Duration.ofMinutes(1));

    /** 자동 turn 의 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    /** 시도 하나를 닫다 실패하는 경우를 만들려고 감싼다. 그 밖의 검사에서는 실제 동작 그대로다. */
    @Autowired
    ResultDeliveryAttemptRepository attempts;

    @Autowired
    ResultDeliveryRecorder recorder;

    @Autowired
    RecoveredRunRecorder recovered;

    @Autowired
    NextTurnDispatcher dispatcher;

    @Autowired
    TurnCancellation turns;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

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
    ChatArtifactRepository artifactRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    ResultDeliveryRepository deliveries;

    @Autowired
    ResultDeliveryItemRepository deliveryItems;

    @Autowired
    HermesRunsClient hermes;

    private AppUser dad;
    private Agent chief;
    private Agent worker;
    private Conversation conversation;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        // 이 문맥이 뜰 때 기동 훑기가 앞 검사들이 남긴 결과로 연 turn 이 있을 수 있다. 지우기 전에 끝나기를 기다린다.
        awaitAllIdle();
        stub().reset();
        deliveryItems.deleteAll();
        attempts.deleteAll();
        deliveries.deleteAll();
        artifactRows.deleteAll();
        pendingMessages.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        dad = users.save(AppUser.of("recovery-dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        chief = agents.save(agent("dad", "비서"));
        worker = agents.save(agent("worker", "조사원"));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        stub().willReturn(result("completed", "정리한 답"));
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
    }

    @Test
    @DisplayName("실행 줄 없이 남은 시도는 FAILED 와 INTERRUPTED 로 닫히고 묶음도 FAILED 가 된다")
    void closesLeftoverWithoutExecutionAsInterrupted() {
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, null);

        int closed = recorder.closeLeftovers(STARTED_AT);

        assertThat(closed).as("닫은 시도 수").isEqualTo(1);
        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.errorCode()).isEqualTo(ResultDeliveryRecorder.INTERRUPTED);
        assertThat(attempt.finishedAt()).as("닫은 시각").isNotNull();
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("기준 시각과 같거나 뒤에 시작한 시도는 이 프로세스의 것이라 RUNNING 과 DELIVERING 으로 남는다")
    void keepsAttemptsStartedAtOrAfterStartup() {
        ResultDeliveryAttempt atStart = leftover(conversation, STARTED_AT, null);
        ResultDeliveryAttempt afterStart = leftover(conversation, AFTER_START, null);

        int closed = recorder.closeLeftovers(STARTED_AT);

        assertThat(closed).as("닫은 시도 수").isZero();
        for (ResultDeliveryAttempt left : List.of(atStart, afterStart)) {
            ResultDeliveryAttempt attempt = reload(left);
            assertThat(attempt.status())
                    .as("%s 에 시작한 시도", attempt.startedAt())
                    .isEqualTo(DeliveryAttemptStatus.RUNNING);
            assertThat(attempt.finishedAt()).isNull();
            assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.DELIVERING);
        }
    }

    @Test
    @DisplayName("실행 줄은 성공으로 끝났는데 시도가 남았으면 시도는 SUCCEEDED, 묶음은 DELIVERED 로 닫힌다")
    void closesLeftoverOfSucceededExecutionAsSucceeded() {
        AgentExecution parent = chatTurn(ExecutionStatus.SUCCEEDED);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, parent.id());

        int closed = recorder.closeLeftovers(STARTED_AT);

        assertThat(closed).isEqualTo(1);
        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.SUCCEEDED);
        assertThat(attempt.errorCode()).isNull();
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("실행 줄이 취소로 끝났으면 시도와 묶음이 STOPPED 로 닫힌다")
    void closesLeftoverOfCancelledExecutionAsStopped() {
        AgentExecution parent = chatTurn(ExecutionStatus.CANCELLED);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, parent.id());

        recorder.closeLeftovers(STARTED_AT);

        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.STOPPED);
        assertThat(attempt.errorCode()).isNull();
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.STOPPED);
    }

    @Test
    @DisplayName("이은 실행 줄이 지워졌으면 FAILED 와 INTERRUPTED 로 닫힌다")
    void closesLeftoverOfDeletedExecutionAsInterrupted() {
        AgentExecution parent = chatTurn(ExecutionStatus.SUCCEEDED);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, parent.id());
        executions.deleteById(parent.id());

        recorder.closeLeftovers(STARTED_AT);

        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.errorCode()).isEqualTo(ResultDeliveryRecorder.INTERRUPTED);
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("실행 줄이 아직 돌면 기동 때 건너뛰고, 기동 정리가 실패로 정할 때 시도가 그 오류 코드로 닫힌다")
    void leavesRunningExecutionToRestartReconcileAndClosesWhenItFails() {
        AgentExecution parent = chatTurn(ExecutionStatus.RUNNING);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, parent.id());

        int closed = recorder.closeLeftovers(STARTED_AT);

        assertThat(closed).as("도는 실행 줄의 시도는 건너뛴다").isZero();
        assertThat(reload(left).status()).isEqualTo(DeliveryAttemptStatus.RUNNING);
        assertThat(deliveryOf(left).status()).isEqualTo(DeliveryStatus.DELIVERING);

        boolean written = recovered.failWithout(parent.id(), "REMOTE_RUN_LOST");

        assertThat(written).as("RUNNING 줄을 적었다").isTrue();
        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.errorCode()).isEqualTo("REMOTE_RUN_LOST");
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("기동 정리가 성공으로 정하면 실행 줄과 답과 함께 시도는 SUCCEEDED, 묶음은 DELIVERED 로 닫힌다")
    void closesAttemptWhenRestartReconcileSettlesSucceeded() {
        AgentExecution parent = chatTurn(ExecutionStatus.RUNNING);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, parent.id());

        boolean written = recovered.settle(parent.id(), result("completed", "다시 붙어 받은 답"));

        assertThat(written).isTrue();
        assertThat(executions.findById(parent.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .filteredOn(message -> message.role() == MessageRole.ASSISTANT)
                .extracting(ChatMessage::content, ChatMessage::executionId)
                .containsExactly(tuple("다시 붙어 받은 답", parent.id()));
        ResultDeliveryAttempt attempt = reload(left);
        assertThat(attempt.status()).isEqualTo(DeliveryAttemptStatus.SUCCEEDED);
        assertThat(attempt.errorCode()).isNull();
        assertThat(deliveryOf(attempt).status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("닫은 묶음의 결과는 기동 뒤 깨우기가 다시 열지 않고, 전하지 않은 결과만 연다")
    void doesNotReopenClosedDeliveryAfterStartup() {
        AgentExecution root = chatTurn(ExecutionStatus.SUCCEEDED);
        AgentExecution child = delegated(conversation, root);
        deliveryWriter.markResultDelivered(child.id(), BEFORE_START);
        ResultDeliveryAttempt left = leftover(conversation, BEFORE_START, null);
        deliveryItems.save(ResultDeliveryItem.of(
                left.deliveryId(), ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(child.id())));

        recorder.closeLeftovers(STARTED_AT);
        dispatcher.dispatchAfterStartup();
        awaitIdle(conversation.id());

        assertThat(deliveryOf(left).status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(stub().received()).as("닫은 묶음의 대화에서 대역 Hermes 제출 수").isEmpty();

        // 대조: 전했다는 표시만 비운 결과는 같은 훑기로 열린다. 앞의 0 이 훑기를 실제로 거친 결과임을 보인다.
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "다른 대화", chief.id(), Instant.now()));
        AgentExecution otherRoot = chatTurn(other, ExecutionStatus.SUCCEEDED);
        delegated(other, otherRoot);

        dispatcher.dispatchAfterStartup();
        awaitIdle(other.id());

        assertThat(stub().received()).as("대조 대화까지 깨운 뒤 전체 제출 수").hasSize(1);
    }

    @Test
    @DisplayName("기동 이벤트에서 빈을 만든 시각 전에 시작한 시도만 닫는다")
    void closesOnlyAttemptsStartedBeforeBeanCreationOnStartup() {
        ResultDeliveryAttempt before = leftover(conversation, BEFORE_START, null);
        ResultDeliveryAttempt after = leftover(conversation, AFTER_START, null);
        ResultDeliveryRecovery recovery = new ResultDeliveryRecovery(recorder, Clock.fixed(STARTED_AT, ZoneOffset.UTC));

        recovery.closeAfterStartup();

        assertThat(reload(before).status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(reload(before).errorCode()).isEqualTo(ResultDeliveryRecorder.INTERRUPTED);
        assertThat(reload(after).status()).isEqualTo(DeliveryAttemptStatus.RUNNING);
    }

    @Test
    @DisplayName("기동 때 정리가 예외를 던져도 기동 이벤트는 예외 없이 끝난다")
    void keepsStartupRunningWhenClosingFails() {
        ResultDeliveryRecorder broken = mock(ResultDeliveryRecorder.class);
        when(broken.closeLeftovers(any())).thenThrow(new IllegalStateException("표를 읽지 못했다"));
        ResultDeliveryRecovery recovery = new ResultDeliveryRecovery(broken, Clock.fixed(STARTED_AT, ZoneOffset.UTC));

        assertThatCode(recovery::closeAfterStartup).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 시도를 닫다 실패해도 나머지 시도는 닫고 닫은 수만 센다")
    void continuesWithNextAttemptWhenOneFails() {
        ResultDeliveryAttempt broken = leftover(conversation, BEFORE_START, null);
        ResultDeliveryAttempt fine = leftover(conversation, BEFORE_START, null);
        doThrow(new IllegalStateException("시도를 닫지 못했다")).when(attempts).finish(eq(broken.id()), any(), any(), any());

        int closed = recorder.closeLeftovers(STARTED_AT);

        assertThat(closed).as("닫은 시도 수").isEqualTo(1);
        assertThat(reload(broken).status()).isEqualTo(DeliveryAttemptStatus.RUNNING);
        assertThat(deliveryOf(broken).status()).as("되돌아간 묶음").isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(reload(fine).status()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(deliveryOf(fine).status()).isEqualTo(DeliveryStatus.FAILED);
    }

    /** 이전 프로세스가 남긴 것처럼 도는 중인 묶음과 첫 시도를 만든다. 실행 번호가 있으면 시도에 잇는다. */
    private ResultDeliveryAttempt leftover(Conversation owner, Instant startedAt, Long executionId) {
        ResultDelivery delivery = deliveries.save(ResultDelivery.opened(owner.id(), startedAt));
        ResultDeliveryAttempt attempt = attempts.save(ResultDeliveryAttempt.started(delivery.id(), 1, null, startedAt));
        if (executionId != null) {
            recorder.attachExecution(attempt.id(), executionId);
        }
        return attempt;
    }

    private ResultDeliveryAttempt reload(ResultDeliveryAttempt attempt) {
        return attempts.findById(attempt.id()).orElseThrow();
    }

    private ResultDelivery deliveryOf(ResultDeliveryAttempt attempt) {
        return deliveries.findById(attempt.deliveryId()).orElseThrow();
    }

    private AgentExecution chatTurn(ExecutionStatus status) {
        return chatTurn(conversation, status);
    }

    /** 대화 turn 의 루트 줄이다. 기동 정리가 물을 수 있게 run 번호를 둔다. */
    private AgentExecution chatTurn(Conversation owner, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(owner.id())
                .agentId(chief.id())
                .profileName(chief.hermesProfile())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(BEFORE_START)
                .build());
    }

    /** 대화 turn 이 직접 맡겨 성공으로 끝난 위임 실행 줄이다. 전했다는 표시는 비어 있다. */
    private AgentExecution delegated(Conversation owner, AgentExecution root) {
        AgentExecution execution = AgentExecution.builder()
                .userId(dad.id())
                .conversationId(owner.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .delegationKey(UUID.randomUUID().toString())
                .profileName(worker.hermesProfile())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(BEFORE_START)
                .build();
        execution.recordOutput("조사 결과");
        return executions.save(execution);
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

    private static HermesRunResult result(String status, String output) {
        return HermesRunResult.of("run-1", "sess-1", status, output, "model", "provider", TokenUsage.empty());
    }

    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitIdle(it.id()));
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
