package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnIntent;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.TaskRunStarter;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.usage.application.TurnSlot;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시작 단계가 {@code QUEUED} 발화를 작업 주인의 대화 turn 으로 여는지 실제 DB 와 가짜 Hermes 로 본다. 규칙은
 * {@code docs/backend/task.md} 의 「시작」 과 「알림」 이다.
 *
 * <p>사용자 실행 한도를 2 로 두고, 자리를 채울 때는 그 사용자의 turn 자리 둘을 먼저 얻어 둔다. 시각은 검사가
 * {@link TaskRunStarter#startQueued} 에 넘기는 값이 정한다. turn 은 가상 스레드에서 돌므로 발화 줄이 끝날 때까지 기다린다.
 */
@SpringBootTest(properties = "assistant.user-execution.max-running=2")
@ActiveProfiles("test")
@Import(TaskRunStarterTest.StubHermes.class)
class TaskRunStarterTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-01T00:01:00Z");
    private static final Instant SCHEDULED = Instant.parse("2026-11-01T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String TITLE = "매달 정리";
    private static final String INSTRUCTION = "지난달 기록을 보고 정리해 줘";

    @TestConfiguration
    static class StubHermes {
        @Bean
        @Primary
        StubHermesRunsClient taskStubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    /** 답 조각은 이 검사가 보지 않는다. 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @MockitoBean
    HermesRunEventStream eventStream;

    /** 시작 단계가 대화를 만드는 사이에 다른 트랜잭션을 끼워 넣는다. 끼우지 않은 검사에서는 실제 메서드가 그대로 돈다. */
    @MockitoSpyBean
    ChatService chat;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TaskRunStarter starter;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TurnCancellation turns;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskTriggerRepository triggers;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    NotificationRepository notifications;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<TurnSlot> heldSlots = new ArrayList<>();

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        stub().willReturn(HermesRunResult.of(
                "run-task", "session", "completed", "정리한 답", "model", "provider", TokenUsage.empty()));
        cleanTasks();
    }

    @AfterEach
    void tearDown() {
        heldSlots.forEach(TurnSlot::release);
        heldSlots.clear();
        awaitNoRunning();
        cleanTasks();
        createdUsers.forEach(userId -> notifications.deleteAll(notificationsOf(userId)));
        createdUsers.clear();
    }

    @Test
    @DisplayName("QUEUED 를 열면 작업의 새 대화에 알림 줄, 지시, 답이 남고 SUCCEEDED 와 실행 번호가 적히며 ALWAYS 면 TASK_SUCCEEDED 하나다")
    void opensQueuedRunInNewConversationAndNotifiesSuccess() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);

        int started = starter.startQueued(NOW);

        assertThat(started).as("연 줄 수").isEqualTo(1);
        TaskRun finished = awaitFinished(run);
        assertThat(finished.status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(finished.executionId()).as("루트 실행 번호").isNotNull();
        assertThat(finished.startedAt()).isEqualTo(NOW);
        assertThat(finished.finishedAt()).isNotNull();
        Conversation conversation =
                conversations.findById(finished.conversationId()).orElseThrow();
        assertThat(conversation.taskId()).as("대화의 작업").isEqualTo(fixture.task().id());
        assertThat(conversation.title()).as("대화 제목").isEqualTo(TITLE);
        assertThat(conversation.autoTurnCount()).as("자동 turn 수").isZero();
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(history)
                .extracting(ChatMessage::content)
                .containsExactly("예약 작업 「" + TITLE + "」 을 시작했어요", INSTRUCTION, "정리한 답");
        assertThat(stub().received().getLast().instructions())
                .as("Hermes 에 간 지시")
                .contains(TurnIntent.SCHEDULED_INSTRUCTION);
        assertThat(notificationsOf(fixture.owner().id())).singleElement().satisfies(notification -> {
            assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_SUCCEEDED);
            assertThat(notification.title()).isEqualTo("「" + TITLE + "」 실행을 마쳤어요");
            assertThat(notification.body()).isEmpty();
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.CONVERSATION);
            assertThat(notification.targetPublicId()).isEqualTo(conversation.publicId());
        });
    }

    @Test
    @DisplayName("알림이 ON_FAILURE 면 성공한 발화를 알리지 않는다")
    void doesNotNotifySuccessOnFailureOnly() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ON_FAILURE);
        TaskRun run = queued(fixture, SCHEDULED, NOW);

        starter.startQueued(NOW);

        assertThat(awaitFinished(run).status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(notificationsOf(fixture.owner().id())).isEmpty();
    }

    @Test
    @DisplayName("자리를 얻지 못하고 시작 시간을 넘긴 NEW_PER_RUN 발화의 빈 대화를 지우고 BUSY 로 알린다")
    void staysQueuedWhileUserBusyThenSkipsAfterTimeout() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());

        int first = starter.startQueued(NOW);
        int second = starter.startQueued(NOW.plus(Duration.ofMinutes(5)));

        assertThat(first + second).as("연 줄 수").isZero();
        TaskRun waiting = runs.findById(run.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(TaskRunStatus.QUEUED);
        assertThat(waiting.conversationId()).as("줄에 남은 대화").isNotNull();
        assertThat(conversationsOf(fixture.task())).as("작업의 대화").hasSize(1);
        assertThat(stub().received()).as("Hermes 제출").isEmpty();

        starter.startQueued(NOW.plus(Duration.ofMinutes(10)));

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.reason()).isEqualTo(TaskRunReason.BUSY);
        assertThat(skipped.conversationId()).isNull();
        assertThat(conversations.findById(waiting.conversationId())).isEmpty();
        assertThat(stub().received()).isEmpty();
        assertThat(notificationsOf(fixture.owner().id())).singleElement().satisfies(notification -> {
            assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_SKIPPED);
            assertThat(notification.body()).isEqualTo("다른 대화가 오래 돌고 있어 시작하지 못했어요");
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.TASK);
            assertThat(notification.targetPublicId()).isEqualTo(fixture.task().publicId());
        });
    }

    @Test
    @DisplayName("기다리는 동안 작업을 멈추면 빈 대화를 지운다")
    void discardsEmptyConversationWhenWaitingTaskPaused() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.NEVER);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());
        starter.startQueued(NOW);
        Long conversationId = runs.findById(run.id()).orElseThrow().conversationId();
        Task task = tasks.findById(fixture.task().id()).orElseThrow();
        task.pause(NOW);
        tasks.save(task);
        heldSlots.forEach(TurnSlot::release);
        heldSlots.clear();

        starter.startQueued(NOW.plusSeconds(30));

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.reason()).isEqualTo(TaskRunReason.PAUSED);
        assertThat(skipped.conversationId()).isNull();
        assertThat(conversations.findById(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("SINGLE 발화가 건너뛰어지면 대화를 지우지 않는다")
    void keepsSingleConversationWhenRunSkipped() {
        Fixture fixture = fixture(ConversationMode.SINGLE, NotifyPolicy.NEVER);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());
        starter.startQueued(NOW);
        Long conversationId = runs.findById(run.id()).orElseThrow().conversationId();

        starter.startQueued(NOW.plus(Duration.ofMinutes(10)));

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.reason()).isEqualTo(TaskRunReason.BUSY);
        assertThat(skipped.conversationId()).isEqualTo(conversationId);
        assertThat(tasks.findById(fixture.task().id()).orElseThrow().conversationId())
                .isEqualTo(conversationId);
        assertThat(conversations.findById(conversationId)).isPresent();
    }

    @Test
    @DisplayName("메시지가 있는 NEW_PER_RUN 대화는 발화를 건너뛰어도 지우지 않는다")
    void keepsConversationWithMessagesWhenRunSkipped() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.NEVER);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());
        starter.startQueued(NOW);
        Long conversationId = runs.findById(run.id()).orElseThrow().conversationId();
        messages.save(ChatMessage.fromUser(conversationId, fixture.owner().id(), "남겨 둔 메시지", NOW));

        starter.startQueued(NOW.plus(Duration.ofMinutes(10)));

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.conversationId()).isEqualTo(conversationId);
        assertThat(conversations.findById(conversationId)).isPresent();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversationId)).hasSize(1);
    }

    @Test
    @DisplayName("기다리는 동안 에이전트를 바꾸면 새 에이전트의 새 대화로 돈다")
    void replacesWaitingConversationWhenAgentChanged() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.NEVER);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());
        starter.startQueued(NOW);
        Long previousId = runs.findById(run.id()).orElseThrow().conversationId();
        String code = "replacement-" + UUID.randomUUID().toString().substring(0, 8);
        Agent replacement = agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                fixture.owner().id(),
                NOW));
        Task task = tasks.findById(fixture.task().id()).orElseThrow();
        task.edit(
                replacement.id(), task.title(), task.instruction(), task.conversationMode(), task.notifyPolicy(), NOW);
        tasks.save(task);
        heldSlots.forEach(TurnSlot::release);
        heldSlots.clear();

        starter.startQueued(NOW.plusSeconds(30));

        TaskRun finished = awaitFinished(run);
        assertThat(finished.status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(finished.conversationId()).isNotEqualTo(previousId);
        assertThat(conversations
                        .findById(finished.conversationId())
                        .orElseThrow()
                        .agentId())
                .isEqualTo(replacement.id());
        assertThat(conversations.findById(previousId)).isEmpty();
    }

    @Test
    @DisplayName("QUEUED 로 기다리는 동안 줄의 대화를 지우면 다음에 열 때 새 대화로 SUCCEEDED 가 되고 알림이 새 대화를 가리킨다")
    void opensInNewConversationWhenWaitingConversationDeleted() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        fillSlots(fixture.owner());
        starter.startQueued(NOW);
        Long deleted = runs.findById(run.id()).orElseThrow().conversationId();
        assertThat(deleted).as("기다리는 줄의 대화").isNotNull();
        assertThat(conversationWriter.deleteIfActive(deleted, fixture.owner().id(), NOW))
                .as("지운 대화 수")
                .isEqualTo(1);
        heldSlots.forEach(TurnSlot::release);
        heldSlots.clear();

        int started = starter.startQueued(NOW.plus(Duration.ofMinutes(1)));

        assertThat(started).as("연 줄 수").isEqualTo(1);
        TaskRun finished = awaitFinished(run);
        assertThat(finished.status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(finished.conversationId()).as("새로 적은 대화").isNotNull().isNotEqualTo(deleted);
        Conversation conversation =
                conversations.findById(finished.conversationId()).orElseThrow();
        assertThat(conversation.taskId())
                .as("새 대화의 작업")
                .isEqualTo(fixture.task().id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(notificationsOf(fixture.owner().id())).singleElement().satisfies(notification -> {
            assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_SUCCEEDED);
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.CONVERSATION);
            assertThat(notification.targetPublicId()).isEqualTo(conversation.publicId());
        });
    }

    @Test
    @DisplayName("놓친 발화로 예정 시각보다 30분 늦게 만든 줄은 만든 때부터 재므로 바로 열린다")
    void opensRunCreatedLateForMissedSchedule() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.NEVER);
        Instant createdLate = SCHEDULED.plus(Duration.ofMinutes(30));
        TaskRun run = queued(fixture, SCHEDULED, createdLate);

        int started = starter.startQueued(createdLate.plus(Duration.ofSeconds(30)));

        assertThat(started).isEqualTo(1);
        assertThat(awaitFinished(run).status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(notificationsOf(fixture.owner().id())).as("NEVER 의 알림").isEmpty();
    }

    @Test
    @DisplayName("에이전트를 끄면 AGENT_UNAVAILABLE 로 건너뛰고 알린다")
    void skipsWhenAgentDisabled() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        Agent agent = agents.findById(fixture.task().agentId()).orElseThrow();
        agent.changeAccess(false, AgentVisibility.PRIVATE, fixture.owner().id());
        agents.save(agent);

        starter.startQueued(NOW);

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.reason()).isEqualTo(TaskRunReason.AGENT_UNAVAILABLE);
        assertThat(notificationsOf(fixture.owner().id()))
                .extracting(Notification::kind)
                .containsExactly(NotificationKind.TASK_SKIPPED);
        assertThat(conversationsOf(fixture.task())).as("작업의 대화").isEmpty();
    }

    @Test
    @DisplayName("주인을 허용 목록에서 끄면 OWNER_REVOKED 로 건너뛰고 알리지 않는다")
    void skipsWithoutNoticeWhenOwnerRevoked() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        AllowedPerson person = AllowedPerson.of(
                fixture.owner().email(), "owner", fixture.owner().email(), NOW);
        person.disable();
        people.save(person);

        try {
            starter.startQueued(NOW);

            TaskRun skipped = runs.findById(run.id()).orElseThrow();
            assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
            assertThat(skipped.reason()).isEqualTo(TaskRunReason.OWNER_REVOKED);
            assertThat(notificationsOf(fixture.owner().id())).isEmpty();
        } finally {
            people.delete(person);
        }
    }

    @Test
    @DisplayName("멈춘 작업의 QUEUED 는 PAUSED 로 건너뛴다")
    void skipsQueuedRunOfPausedTask() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        Task task = tasks.findById(fixture.task().id()).orElseThrow();
        task.pause(NOW);
        tasks.save(task);

        starter.startQueued(NOW);

        TaskRun skipped = runs.findById(run.id()).orElseThrow();
        assertThat(skipped.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(skipped.reason()).isEqualTo(TaskRunReason.PAUSED);
        assertThat(notificationsOf(fixture.owner().id())).isEmpty();
    }

    @Test
    @DisplayName("Hermes 가 실패하면 FAILED 로 적고 그 대화를 가리키는 TASK_FAILED 를 알린다")
    void recordsFailureAndNotifies() {
        Fixture fixture = fixture(ConversationMode.NEW_PER_RUN, NotifyPolicy.ON_FAILURE);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        stub().willFail(new ApiException(ErrorCode.HERMES_RUN_FAILED, "boom"));

        starter.startQueued(NOW);

        TaskRun failed = awaitFinished(run);
        assertThat(failed.status()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(failed.reason()).isEqualTo(TaskRunReason.FAILED);
        Conversation conversation =
                conversations.findById(failed.conversationId()).orElseThrow();
        assertThat(notificationsOf(fixture.owner().id())).singleElement().satisfies(notification -> {
            assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_FAILED);
            assertThat(notification.title()).isEqualTo("「" + TITLE + "」 실행이 실패했어요");
            assertThat(notification.body()).isEqualTo("실행 중에 문제가 생겼어요");
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.CONVERSATION);
            assertThat(notification.targetPublicId()).isEqualTo(conversation.publicId());
        });
    }

    @Test
    @DisplayName("SINGLE 작업은 두 번째 발화가 첫 발화의 대화에 이어진다")
    void continuesSingleConversationOnSecondRun() {
        Fixture fixture = fixture(ConversationMode.SINGLE, NotifyPolicy.NEVER);
        TaskRun first = queued(fixture, SCHEDULED, NOW);
        starter.startQueued(NOW);
        Long firstConversation = awaitFinished(first).conversationId();

        Instant nextMonth = Instant.parse("2026-12-01T00:00:00Z");
        TaskRun second = queued(fixture, nextMonth, nextMonth);
        starter.startQueued(nextMonth);
        TaskRun finished = awaitFinished(second);

        assertThat(finished.status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(finished.conversationId()).as("두 번째 발화의 대화").isEqualTo(firstConversation);
        assertThat(tasks.findById(fixture.task().id()).orElseThrow().conversationId())
                .as("작업에 적은 대화")
                .isEqualTo(firstConversation);
        assertThat(messages.findByConversationIdOrderByIdAsc(firstConversation))
                .extracting(ChatMessage::role)
                .containsExactly(
                        MessageRole.SYSTEM,
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.SYSTEM,
                        MessageRole.USER,
                        MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("SINGLE 작업이 첫 발화로 대화를 적는 사이 사용자가 작업을 멈추고 이름을 바꿔도 그 변경이 남는다")
    void keepsUserEditCommittedWhileSingleRunRecordsConversation() {
        Fixture fixture = fixture(ConversationMode.SINGLE, NotifyPolicy.NEVER);
        TaskRun run = queued(fixture, SCHEDULED, NOW);
        String renamed = "이름을 바꾼 정리";
        Instant edited = NOW.plusNanos(1_234_567);
        TransactionTemplate userTransaction = new TransactionTemplate(transactionManager);
        userTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
                    // 시작 단계의 준비 트랜잭션이 작업을 읽은 뒤, 커밋하기 전에 사용자의 수정이 먼저 커밋된다.
                    userTransaction.executeWithoutResult(status -> {
                        Task task = tasks.findById(fixture.task().id()).orElseThrow();
                        task.edit(
                                task.agentId(),
                                renamed,
                                task.instruction(),
                                task.conversationMode(),
                                task.notifyPolicy(),
                                edited);
                        task.pause(edited);
                    });
                    return invocation.callRealMethod();
                })
                .when(chat)
                .startForTask(
                        anyLong(), anyLong(), anyString(), eq(fixture.task().id()));

        starter.startQueued(NOW);
        TaskRun finished = awaitFinished(run);

        Task task = tasks.findById(fixture.task().id()).orElseThrow();
        assertThat(task.state()).as("사용자가 멈춘 상태").isEqualTo(TaskState.PAUSED);
        assertThat(task.title()).as("사용자가 바꾼 이름").isEqualTo(renamed);
        assertThat(task.conversationId()).as("작업에 적은 대화").isNotNull().isEqualTo(finished.conversationId());
    }

    /** 그 사용자의 turn 자리 둘을 먼저 얻어 한도를 채운다. 정리 단계가 돌려준다. */
    private void fillSlots(AppUser owner) {
        heldSlots.add(limiter.acquireTurn(owner.id()));
        heldSlots.add(limiter.acquireTurn(owner.id()));
        assertThat(limiter.hasTurnRoom(owner.id())).as("채운 뒤 남은 자리").isFalse();
    }

    /** 그 줄이 {@code QUEUED} 나 {@code RUNNING} 을 벗어나고 대화의 turn 이 닫힐 때까지 기다린다. */
    private TaskRun awaitFinished(TaskRun run) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (true) {
            TaskRun current = runs.findById(run.id()).orElseThrow();
            boolean open = current.status() == TaskRunStatus.QUEUED || current.status() == TaskRunStatus.RUNNING;
            boolean turnOpen = current.conversationId() != null
                    && turns.markOf(current.conversationId()).running();
            if (!open && !turnOpen) {
                return current;
            }
            if (System.nanoTime() > deadline) {
                fail("발화 %d 가 %s 안에 끝나지 않았다. 상태 %s", run.id(), WAIT_LIMIT, current.status());
            }
            pause();
        }
    }

    private void awaitNoRunning() {
        runs.findAll().stream()
                .filter(run -> run.status() == TaskRunStatus.RUNNING)
                .forEach(this::awaitFinished);
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("기다리는 중에 끊겼다");
        }
    }

    private TaskRun queued(Fixture fixture, Instant scheduledFor, Instant createdAt) {
        return runs.save(TaskRun.queued(
                fixture.task().id(), fixture.trigger().id(), fixture.owner().id(), scheduledFor, createdAt));
    }

    private Fixture fixture(ConversationMode mode, NotifyPolicy notify) {
        String email = "starter-" + UUID.randomUUID() + "@example.com";
        AppUser owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(owner.id());
        String code = "starter-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
        Task task = tasks.save(Task.create(owner.id(), agent.id(), TITLE, INSTRUCTION, mode, notify, NOW));
        TaskTrigger trigger = triggers.save(TaskTrigger.cron(
                task.id(), "0 9 1 * *", SEOUL, MissedPolicy.RUN_ONCE, Instant.parse("2026-12-01T00:00:00Z"), NOW));
        return new Fixture(owner, task, trigger);
    }

    private List<Conversation> conversationsOf(Task task) {
        return conversations.findAll().stream()
                .filter(conversation -> task.id().equals(conversation.taskId()))
                .toList();
    }

    private List<Notification> notificationsOf(Long userId) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100));
    }

    private void cleanTasks() {
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
    }

    private record Fixture(AppUser owner, Task task, TaskTrigger trigger) {}
}
