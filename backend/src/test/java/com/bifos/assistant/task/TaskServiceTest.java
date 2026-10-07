package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.model.ScheduleInput;
import com.bifos.assistant.task.application.model.TaskDetail;
import com.bifos.assistant.task.application.model.TaskInput;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 작업을 만들고 고치고 멈추고 지우는 규칙을 실제 DB 로 본다. 규칙은 {@code docs/backend/task.md} 의 「작업」 과 「시각」 이다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자와 에이전트는 검사마다 새로 만들어 다른 검사의 줄과 섞이지 않게 한다.
 */
@BackendIntegrationTest
class TaskServiceTest {

    /** 2026-10-04 서울 9시다. 나노초를 붙여 저장한 시각이 마이크로초로 잘리는지 본다. */
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00.123456789Z");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    TestClock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TaskService service;

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

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("만들면 작업과 시각이 함께 생기고 다음 시각은 지금 뒤의 첫 예정 시각이다")
    void createsTaskAndTriggerWithNextFireAt() {
        CurrentUser owner = member();
        agentOf(owner, AgentVisibility.PRIVATE);

        TaskDetail created = service.create(owner, monthly("  지난달 기록 정리  ", owner));

        Task stored = tasks.findById(created.task().id()).orElseThrow();
        TaskTrigger trigger = triggers.findByTaskId(stored.id()).orElseThrow();
        assertThat(stored.title()).isEqualTo("지난달 기록 정리");
        assertThat(stored.ownerUserId()).isEqualTo(owner.id());
        assertThat(stored.state()).isEqualTo(TaskState.ACTIVE);
        assertThat(stored.conversationMode()).isEqualTo(ConversationMode.NEW_PER_RUN);
        assertThat(stored.notifyPolicy()).isEqualTo(NotifyPolicy.ALWAYS);
        assertThat(stored.createdAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(trigger.type()).isEqualTo(TriggerType.CRON);
        assertThat(trigger.cronExpr()).isEqualTo("0 9 1 * *");
        assertThat(trigger.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(trigger.missedPolicy()).isEqualTo(MissedPolicy.RUN_ONCE);
        assertThat(trigger.nextFireAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(trigger.createdAt()).isEqualTo(created.trigger().createdAt());
    }

    @Test
    @DisplayName("한 번 도는 작업의 다음 시각은 그 시각이고, 이미 지난 시각은 TASK_SCHEDULE_INVALID 다")
    void onceUsesFireAtAndRejectsPast() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);

        TaskDetail created = service.create(owner, once("내일 알림", agent, LocalDateTime.parse("2026-10-05T09:00")));

        assertThat(created.trigger().nextFireAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(created.trigger().fireAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertCode(
                () -> service.create(owner, once("지난 알림", agent, LocalDateTime.parse("2026-10-04T08:59"))),
                ErrorCode.TASK_SCHEDULE_INVALID);
    }

    @Test
    @DisplayName("다음 시각이 없는 cron 과 15분보다 촘촘한 cron 은 TASK_SCHEDULE_INVALID 다")
    void rejectsCronWithoutNextOrTooFrequent() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);

        assertCode(() -> service.create(owner, cron("2월 31일", agent, "0 9 31 2 *")), ErrorCode.TASK_SCHEDULE_INVALID);
        assertCode(() -> service.create(owner, cron("5분마다", agent, "*/5 * * * *")), ErrorCode.TASK_SCHEDULE_INVALID);
        assertThat(tasks.countByOwnerUserIdAndKindAndStateNot(owner.id(), TaskKind.TURN, TaskState.ARCHIVED))
                .isZero();
    }

    @Test
    @DisplayName("11번째 작업은 TASK_LIMIT_REACHED 이고 하나를 지우면 다시 만들 수 있다")
    void limitsActiveTasksPerUserAndArchivedDoNotCount() {
        CurrentUser owner = member();
        agentOf(owner, AgentVisibility.PRIVATE);
        UUID first = null;
        for (int i = 0; i < 10; i++) {
            TaskDetail created = service.create(owner, monthly("작업 " + i, owner));
            first = first == null ? created.task().publicId() : first;
        }

        assertCode(() -> service.create(owner, monthly("열한째", owner)), ErrorCode.TASK_LIMIT_REACHED);

        service.archive(owner, first);
        TaskDetail again = service.create(owner, monthly("열한째", owner));
        assertThat(again.task().title()).isEqualTo("열한째");
        assertThat(service.list(owner)).hasSize(10);
    }

    @Test
    @DisplayName("작업이 10개인 사용자도 기존 작업을 고칠 수 있다")
    void updatesExistingTaskAtLimit() {
        CurrentUser owner = member();
        agentOf(owner, AgentVisibility.PRIVATE);
        TaskDetail last = null;
        for (int i = 0; i < 10; i++) {
            last = service.create(owner, monthly("작업 " + i, owner));
        }

        TaskDetail updated = service.update(owner, last.task().publicId(), monthly("고친 이름", owner));

        assertThat(updated.task().title()).isEqualTo("고친 이름");
    }

    @Test
    @DisplayName("꺼진 에이전트는 AGENT_DISABLED, 흐름 에이전트는 TASK_AGENT_NOT_SUPPORTED, 남의 비공개 에이전트는 AGENT_NOT_FOUND 다")
    void rejectsUnusableAgents() {
        CurrentUser owner = member();
        CurrentUser other = member();
        Agent off = agentOf(owner, AgentVisibility.PRIVATE);
        off.changeAccess(false, AgentVisibility.PRIVATE, owner.id());
        agents.save(off);
        Agent flowed = agentOf(owner, AgentVisibility.PRIVATE);
        flowed.assignFlow("research-and-build");
        agents.save(flowed);
        Agent othersPrivate = agentOf(other, AgentVisibility.PRIVATE);

        assertCode(() -> service.create(owner, cron("꺼짐", off, "0 9 * * *")), ErrorCode.AGENT_DISABLED);
        assertCode(() -> service.create(owner, cron("흐름", flowed, "0 9 * * *")), ErrorCode.TASK_AGENT_NOT_SUPPORTED);
        assertCode(() -> service.create(owner, cron("남의 것", othersPrivate, "0 9 * * *")), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 작업은 조회, 고치기, 멈추기, 다시 켜기, 지우기, 발화 기록이 모두 TASK_NOT_FOUND 다")
    void hidesOthersTasks() {
        CurrentUser owner = member();
        CurrentUser other = member();
        agentOf(owner, AgentVisibility.PRIVATE);
        agentOf(other, AgentVisibility.PRIVATE);
        UUID taskId = service.create(owner, monthly("내 작업", owner)).task().publicId();

        assertCode(() -> service.get(other, taskId), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.update(other, taskId, monthly("가로채기", other)), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.pause(other, taskId), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.resume(other, taskId), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.archive(other, taskId), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.runs(other, taskId, 20), ErrorCode.TASK_NOT_FOUND);
        assertCode(() -> service.get(owner, UUID.randomUUID()), ErrorCode.TASK_NOT_FOUND);
        assertThat(service.list(other)).isEmpty();
        assertThat(tasks.findByPublicIdAndOwnerUserIdAndKind(taskId, owner.id(), TaskKind.TURN)
                        .orElseThrow()
                        .title())
                .isEqualTo("내 작업");
    }

    @Test
    @DisplayName("지운 작업은 목록과 조회에서 빠지고 줄은 ARCHIVED 로 남는다")
    void archiveKeepsRowAndHidesTask() {
        CurrentUser owner = member();
        agentOf(owner, AgentVisibility.PRIVATE);
        TaskDetail created = service.create(owner, monthly("지울 작업", owner));

        service.archive(owner, created.task().publicId());

        Task stored = tasks.findById(created.task().id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(TaskState.ARCHIVED);
        assertThat(stored.archivedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(service.list(owner)).isEmpty();
        assertCode(() -> service.get(owner, created.task().publicId()), ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("멈춘 뒤 사흘 지나 다시 켜면 다음 시각은 그 뒤의 첫 예정 시각이다")
    void resumeRecomputesNextFromNow() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);
        UUID taskId =
                service.create(owner, cron("매일 9시", agent, "0 9 * * *")).task().publicId();
        assertThat(service.get(owner, taskId).trigger().nextFireAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));

        TaskDetail paused = service.pause(owner, taskId);
        assertThat(paused.task().state()).isEqualTo(TaskState.PAUSED);
        assertThat(service.pause(owner, taskId).task().state()).isEqualTo(TaskState.PAUSED);

        clock.set(Instant.parse("2026-10-07T03:00:00Z")); // 서울 10월 7일 12시
        TaskDetail resumed = service.resume(owner, taskId);

        assertThat(resumed.task().state()).isEqualTo(TaskState.ACTIVE);
        assertThat(triggers.findByTaskId(resumed.task().id()).orElseThrow().nextFireAt())
                .isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(service.resume(owner, taskId).task().state()).isEqualTo(TaskState.ACTIVE);
    }

    @Test
    @DisplayName("이름만 고치면 다음 시각이 그대로이고, cron 을 고치면 다시 계산한다")
    void updateKeepsNextUnlessScheduleChanges() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);
        UUID taskId =
                service.create(owner, cron("매일 9시", agent, "0 9 * * *")).task().publicId();
        clock.set(Instant.parse("2026-10-06T03:00:00Z"));

        TaskDetail renamed = service.update(owner, taskId, cron("새 이름", agent, "0 9 * * *"));

        assertThat(renamed.task().title()).isEqualTo("새 이름");
        assertThat(triggers.findByTaskId(renamed.task().id()).orElseThrow().nextFireAt())
                .isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));

        TaskDetail moved = service.update(owner, taskId, cron("새 이름", agent, "0 10 * * *"));

        assertThat(triggers.findByTaskId(moved.task().id()).orElseThrow().nextFireAt())
                .isEqualTo(Instant.parse("2026-10-07T01:00:00Z"));
    }

    @Test
    @DisplayName("이미 발화한 한 번 도는 작업도 같은 시각을 보내며 이름을 고칠 수 있다")
    void renamesFiredOnceTaskWithSameSchedule() {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);
        LocalDateTime fireAt = LocalDateTime.parse("2026-10-04T12:00");
        TaskDetail created = service.create(owner, once("한 번", agent, fireAt));
        TaskTrigger trigger = triggers.findByTaskId(created.task().id()).orElseThrow();
        trigger.advance(trigger.fireAt(), null, Instant.parse("2026-10-04T03:00:00Z"));
        triggers.save(trigger);
        clock.set(Instant.parse("2026-10-05T00:00:00Z"));

        TaskDetail renamed = service.update(owner, created.task().publicId(), once("고친 이름", agent, fireAt));

        TaskTrigger stored = triggers.findByTaskId(renamed.task().id()).orElseThrow();
        assertThat(renamed.task().title()).isEqualTo("고친 이름");
        assertThat(stored.nextFireAt()).isNull();
        assertThat(stored.lastFiredAt()).isEqualTo(Instant.parse("2026-10-04T03:00:00Z"));
        assertCode(
                () -> service.update(owner, created.task().publicId(), once("고친 이름", agent, fireAt.minusHours(1))),
                ErrorCode.TASK_SCHEDULE_INVALID);
    }

    @Test
    @DisplayName("발화 기록의 limit 은 1 이상 100 이하이고 밖이면 VALIDATION_FAILED 다")
    void runsLimitBounds() {
        CurrentUser owner = member();
        agentOf(owner, AgentVisibility.PRIVATE);
        UUID taskId = service.create(owner, monthly("기록", owner)).task().publicId();

        assertThat(service.runs(owner, taskId, 1)).isEmpty();
        assertThat(service.runs(owner, taskId, 100)).isEmpty();
        assertCode(() -> service.runs(owner, taskId, 0), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.runs(owner, taskId, 101), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("고치기가 시작 단계가 적은 대화 번호를 되돌리지 않는다")
    void preservesConversationRecordedWhileUpdateWaits() throws Exception {
        CurrentUser owner = member();
        Agent agent = agentOf(owner, AgentVisibility.PRIVATE);
        LocalDateTime fireAt = LocalDateTime.parse("2026-10-04T12:00");
        Task task = service.create(owner, once("한 번", agent, fireAt)).task();
        Conversation conversation =
                conversations.save(Conversation.startedForTask(owner.id(), "합성 대화", agent.id(), task.id(), NOW));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var record = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    tasks.findByIdForUpdate(task.id()).orElseThrow();
                    locked.countDown();
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    tasks.useConversation(task.id(), conversation.id(), NOW.truncatedTo(ChronoUnit.MICROS));
                });
            });
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var update = executor.submit(() -> {
                    updating.countDown();
                    return service.update(owner, task.publicId(), once("고친 이름", agent, fireAt));
                });
                assertThat(updating.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                assertThat(update.isDone()).as("작업 잠금이 풀리기 전에는 고치기가 끝나지 않는다").isFalse();
                release.countDown();
                record.get(10, TimeUnit.SECONDS);
                update.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        Task stored = tasks.findById(task.id()).orElseThrow();
        assertThat(stored.conversationId()).isEqualTo(conversation.id());
        assertThat(stored.title()).isEqualTo("고친 이름");
    }

    private TaskInput monthly(String title, CurrentUser owner) {
        Agent agent = agents.findAll().stream()
                .filter(it -> owner.id().equals(it.ownerUserId()) && it.enabled() && it.flow() == null)
                .findFirst()
                .orElseThrow();
        return cron(title, agent, "0 9 1 * *");
    }

    private static TaskInput cron(String title, Agent agent, String cron) {
        return new TaskInput(
                title,
                agent.code(),
                "지난달 기록을 보고 정리해 줘",
                new ScheduleInput(TriggerType.CRON, cron, null, null),
                null,
                null,
                null);
    }

    private static TaskInput once(String title, Agent agent, LocalDateTime fireAt) {
        return new TaskInput(
                title,
                agent.code(),
                "알려 줘",
                new ScheduleInput(TriggerType.ONCE, null, fireAt, SEOUL.getId()),
                ConversationMode.SINGLE,
                MissedPolicy.SKIP,
                NotifyPolicy.ON_FAILURE);
    }

    private CurrentUser member() {
        String email = "task-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner, AgentVisibility visibility) {
        String code = "task-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                owner.id(),
                NOW));
    }

    private void clean() {
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
