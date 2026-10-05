package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.notification.domain.Notification;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.TaskDispatcher;
import com.bifos.assistant.task.application.TaskFiring;
import com.bifos.assistant.task.application.TaskNotices;
import com.bifos.assistant.task.application.TaskRunRecovery;
import com.bifos.assistant.task.application.TaskRunStarter;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 정리가 도는 중이던 발화를 닫고, 그 정리가 끝나기 전에는 발화기가 돌지 않는지 본다. 규칙은
 * {@code docs/backend/task.md} 의 「기동할 때」 다.
 *
 * <p>{@code @SpringBootTest} 컨텍스트는 이미 기동 사건을 지나 정리가 끝난 것으로 적혀 있다. 그래서 끝나기 전의 모습은
 * {@link TaskRunRecovery} 와 {@link TaskDispatcher} 를 직접 만들어 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskRunRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:30Z");
    private static final Instant SCHEDULED = Instant.parse("2026-11-01T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    TaskRunRecovery recovery;

    @Autowired
    TaskFiring firing;

    @Autowired
    TaskRunStarter starter;

    @Autowired
    TaskNotices notices;

    @Autowired
    TransactionTemplate transactions;

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
    NotificationRepository notifications;

    @Autowired
    ProactiveCheckRepository checks;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdChecks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
        createdUsers.forEach(userId -> notifications.deleteAll(notificationsOf(userId)));
        createdUsers.clear();
    }

    @Test
    @DisplayName("기동 정리는 RUNNING 줄을 INTERRUPTED 로 닫고 알림 하나를 남기며 QUEUED 줄은 그대로 둔다")
    void closesRunningRunsAndLeavesQueued() {
        Fixture fixture = fixture(NotifyPolicy.ALWAYS);
        TaskRun running =
                TaskRun.queued(fixture.task().id(), fixture.trigger().id(), fixture.ownerId(), SCHEDULED, NOW);
        running.start(NOW);
        running = runs.save(running);
        TaskRun queued = runs.save(TaskRun.queued(
                fixture.task().id(), fixture.trigger().id(), fixture.ownerId(), SCHEDULED.minusSeconds(60), NOW));

        recovery.onReady();

        TaskRun closed = runs.findById(running.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(closed.reason()).isEqualTo(TaskRunReason.INTERRUPTED);
        assertThat(closed.finishedAt()).isNotNull();
        assertThat(runs.findById(queued.id()).orElseThrow().status())
                .as("QUEUED 줄")
                .isEqualTo(TaskRunStatus.QUEUED);
        assertThat(notificationsOf(fixture.ownerId())).singleElement().satisfies(notification -> {
            assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_FAILED);
            assertThat(notification.body()).isEqualTo("서버가 다시 시작돼 실행이 끊겼어요");
            assertThat(notification.targetType()).isEqualTo(NotificationTargetType.TASK);
            assertThat(notification.targetPublicId()).isEqualTo(fixture.task().publicId());
        });
        assertThat(recovery.finished()).isTrue();
    }

    @Test
    @DisplayName("정리가 끝나기 전에는 일정이 발화를 만들지도 열지도 않고, 끝난 뒤에야 돈다")
    void dispatcherWaitsForRecovery() {
        Fixture due = fixture(NotifyPolicy.NEVER);
        // 끝난 뒤 시작 단계가 돌아도 Hermes 를 부르지 않게 에이전트를 꺼 둔다. 그 줄은 AGENT_UNAVAILABLE 로 닫힌다.
        Agent agent = agents.findById(due.task().agentId()).orElseThrow();
        agent.changeAccess(false, AgentVisibility.PRIVATE, due.ownerId());
        agents.save(agent);
        Fixture paused = fixture(NotifyPolicy.NEVER);
        Task pausedTask = tasks.findById(paused.task().id()).orElseThrow();
        pausedTask.pause(NOW);
        tasks.save(pausedTask);
        TaskRun waiting =
                runs.save(TaskRun.queued(paused.task().id(), paused.trigger().id(), paused.ownerId(), SCHEDULED, NOW));
        TaskRunRecovery fresh = new TaskRunRecovery(runs, tasks, notices, checks, transactions, fixedClock());
        TaskDispatcher dispatcher = new TaskDispatcher(firing, starter, fresh, fixedClock());

        dispatcher.runScheduled();

        assertThat(runsOf(due.task())).as("정리 전 발화").isEmpty();
        assertThat(runs.findById(waiting.id()).orElseThrow().status())
                .as("정리 전 QUEUED 줄")
                .isEqualTo(TaskRunStatus.QUEUED);

        fresh.onReady();
        dispatcher.runScheduled();

        assertThat(runsOf(due.task()))
                .as("정리 뒤 발화")
                .singleElement()
                .satisfies(run -> assertThat(run.reason()).isEqualTo(TaskRunReason.AGENT_UNAVAILABLE));
        assertThat(runs.findById(waiting.id()).orElseThrow().reason())
                .as("정리 뒤 멈춘 작업의 줄")
                .isEqualTo(TaskRunReason.PAUSED);
    }

    @Test
    @DisplayName("정리가 예외로 끝나도 끝난 것으로 적어 발화기가 돈다")
    void marksFinishedEvenWhenRecoveryFails() {
        TaskRunRepository broken = mock(TaskRunRepository.class);
        when(broken.findByStatusOrderByScheduledForAscIdAsc(any())).thenThrow(new IllegalStateException("db down"));
        TaskRunRecovery failing = new TaskRunRecovery(broken, tasks, notices, checks, transactions, fixedClock());

        failing.onReady();

        assertThat(failing.finished()).isTrue();
    }

    @Test
    @DisplayName("기동 정리는 읽지 않은 보고로 끝난 CHECK 발화를 INTERRUPTED 대신 건너뜀으로 회복하고 알리지 않는다")
    void recoversUnreadScheduledCheckWithoutCountingItAsInterrupted() {
        Fixture fixture = fixture(NotifyPolicy.ALWAYS);
        Task checkTask = tasks.save(Task.check(fixture.ownerId(), fixture.task().agentId(), "매일 먼저 살펴보기", NOW));
        TaskTrigger checkTrigger = triggers.save(
                TaskTrigger.cron(checkTask.id(), "0 9 * * *", SEOUL, MissedPolicy.SKIP, SCHEDULED, NOW));
        ProactiveCheck check = ProactiveCheck.started(
                fixture.ownerId(), fixture.task().agentId(), 999_999L, CheckTrigger.SCHEDULED, false, NOW);
        check.skip(CheckSkippedReason.UNREAD_REPORT, NOW);
        check = checks.save(check);
        createdChecks.add(check.id());
        TaskRun running = TaskRun.queued(checkTask.id(), checkTrigger.id(), fixture.ownerId(), SCHEDULED, NOW);
        running.useProactiveCheck(check.id());
        running.start(NOW);
        running = runs.save(running);

        recovery.onReady();

        TaskRun recovered = runs.findById(running.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(recovered.reason()).isEqualTo(TaskRunReason.UNREAD_REPORT);
        assertThat(notificationsOf(fixture.ownerId())).isEmpty();
        assertThat(runs.countByOwnerUserIdAndCreatedAtAfterAndStatusNot(
                        fixture.ownerId(), NOW.minusSeconds(1), TaskRunStatus.SKIPPED))
                .isZero();
    }

    @Test
    @DisplayName("기동 정리는 끝난 CHECK의 루트 실행 번호를 연결한 발화에 회복한다")
    void recoversCompletedCheckWithItsRootExecution() {
        Fixture fixture = fixture(NotifyPolicy.NEVER);
        Task checkTask = tasks.save(Task.check(fixture.ownerId(), fixture.task().agentId(), "매일 먼저 살펴보기", NOW));
        TaskTrigger checkTrigger = triggers.save(
                TaskTrigger.cron(checkTask.id(), "0 9 * * *", SEOUL, MissedPolicy.SKIP, SCHEDULED, NOW));
        ProactiveCheck check = ProactiveCheck.started(
                fixture.ownerId(), fixture.task().agentId(), 999_998L, CheckTrigger.SCHEDULED, false, NOW);
        check.attachRoot(7_777L, "scheduled-session");
        check.succeedInvalid(CheckInvalidReason.EMPTY_ANSWER, 0, 0, NOW);
        check = checks.save(check);
        createdChecks.add(check.id());
        TaskRun running = TaskRun.queued(checkTask.id(), checkTrigger.id(), fixture.ownerId(), SCHEDULED, NOW);
        running.useProactiveCheck(check.id());
        running.start(NOW);
        running = runs.save(running);

        recovery.onReady();

        TaskRun recovered = runs.findById(running.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(recovered.executionId()).isEqualTo(7_777L);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private Fixture fixture(NotifyPolicy notify) {
        String email = "recovery-" + UUID.randomUUID() + "@example.com";
        AppUser owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(owner.id());
        String code = "recovery-" + UUID.randomUUID().toString().substring(0, 8);
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
        Task task = tasks.save(
                Task.create(owner.id(), agent.id(), "매달 정리", "정리해 줘", ConversationMode.NEW_PER_RUN, notify, NOW));
        TaskTrigger trigger =
                triggers.save(TaskTrigger.cron(task.id(), "0 9 1 * *", SEOUL, MissedPolicy.RUN_ONCE, SCHEDULED, NOW));
        return new Fixture(owner.id(), task, trigger);
    }

    private List<TaskRun> runsOf(Task task) {
        return runs.findAll().stream()
                .filter(run -> run.taskId().equals(task.id()))
                .toList();
    }

    private List<Notification> notificationsOf(Long userId) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 100));
    }

    private void clean() {
        checks.deleteAllById(createdChecks);
        createdChecks.clear();
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
    }

    private record Fixture(Long ownerId, Task task, TaskTrigger trigger) {}
}
