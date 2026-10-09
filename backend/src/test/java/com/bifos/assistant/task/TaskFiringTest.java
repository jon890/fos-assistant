package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.bifos.assistant.task.application.TaskFiring;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발화기가 예정 시각이 된 작업의 발화를 한 번만 만드는지 실제 DB 로 본다. 규칙은 {@code docs/features/schedule.md} 의 「발화」 다.
 *
 * <p>시각은 검사가 {@link TaskFiring#fireDue} 에 넘기는 값이 정한다. 사용자는 검사마다 새로 만들어 다른 검사의 줄과 섞이지
 * 않게 한다.
 */
@BackendIntegrationTest
class TaskFiringTest {

    /** 매달 1일 서울 9시 예정 시각에서 30초 뒤다. 늦은 것으로 보지 않는 범위 안이다. */
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:30Z");

    private static final Instant NOVEMBER_FIRST = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DECEMBER_FIRST = Instant.parse("2026-12-01T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    TaskFiring firing;

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
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("예정 시각이 된 매달 작업은 QUEUED 하나를 만들고 다음 시각을 12월 1일로 옮기며, 다시 불러도 하나다")
    void firesMonthlyTaskOnceAndMovesNextFireAt() {
        Fixture fixture = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);

        int created = firing.fireDue(NOW);
        int again = firing.fireDue(NOW);

        assertThat(created).as("처음 부른 발화 수").isEqualTo(1);
        assertThat(again).as("같은 시각에 다시 부른 발화 수").isZero();
        assertThat(runsOf(fixture.task())).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TaskRunStatus.QUEUED);
            assertThat(run.scheduledFor()).isEqualTo(NOVEMBER_FIRST);
            assertThat(run.ownerUserId()).isEqualTo(fixture.task().ownerUserId());
        });
        TaskTrigger trigger = triggers.findById(fixture.trigger().id()).orElseThrow();
        assertThat(trigger.nextFireAt()).as("다음 예정 시각").isEqualTo(DECEMBER_FIRST);
        assertThat(trigger.lastFiredAt()).as("마지막 예정 시각").isEqualTo(NOVEMBER_FIRST);
    }

    @Test
    @DisplayName("발화를 만든 뒤 다음 시각을 옮기기 전에 서버가 내려간 것처럼 되돌려도 줄은 하나이고 다음 시각만 다시 옮긴다")
    void keepsOneRunWhenNextFireAtWasNotMoved() {
        Fixture fixture = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        firing.fireDue(NOW);
        TaskTrigger rewound = triggers.findById(fixture.trigger().id()).orElseThrow();
        rewound.moveNext(NOVEMBER_FIRST, NOW);
        triggers.save(rewound);

        int created = firing.fireDue(NOW);

        assertThat(created).as("되돌린 뒤 만든 발화 수").isZero();
        assertThat(runsOf(fixture.task())).as("발화 줄").hasSize(1);
        assertThat(triggers.findById(fixture.trigger().id()).orElseThrow().nextFireAt())
                .as("다시 옮긴 다음 예정 시각")
                .isEqualTo(DECEMBER_FIRST);
    }

    @Test
    @DisplayName("시간대 이름이 틀린 trigger 가 실패해도 다른 trigger 는 발화하고, 실패한 trigger 는 그대로 남는다")
    void firesHealthyTriggerWhenAnotherFails() {
        Fixture broken = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        Fixture healthy = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        jdbc.update(
                "update task_trigger set time_zone = 'Mars/Olympus' where id = ?",
                broken.trigger().id());

        int created = firing.fireDue(NOW);

        assertThat(created).as("만든 발화 수").isEqualTo(1);
        assertThat(runsOf(healthy.task())).as("정상 작업의 발화").hasSize(1);
        assertThat(runsOf(broken.task())).as("실패한 작업의 발화").isEmpty();
        assertThat(triggers.findById(broken.trigger().id()).orElseThrow().nextFireAt())
                .as("실패한 trigger 의 다음 예정 시각")
                .isEqualTo(NOVEMBER_FIRST);
    }

    @Test
    @DisplayName("사흘 늦게 부르면 RUN_ONCE 는 마지막 예정 시각 하나를 열고, SKIP 은 알림 없이 MISSED 하나를 남긴다")
    void handlesMissedRunsByPolicy() {
        Fixture runOnce = daily(MissedPolicy.RUN_ONCE);
        Fixture skip = daily(MissedPolicy.SKIP);
        Instant threeDaysLate = Instant.parse("2026-11-04T03:00:00Z");

        firing.fireDue(threeDaysLate);

        assertThat(runsOf(runOnce.task())).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TaskRunStatus.QUEUED);
            assertThat(run.scheduledFor()).as("지금 앞의 가장 늦은 예정 시각").isEqualTo(Instant.parse("2026-11-04T00:00:00Z"));
        });
        assertThat(runsOf(skip.task())).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TaskRunStatus.SKIPPED);
            assertThat(run.reason()).isEqualTo(TaskRunReason.MISSED);
        });
        assertThat(notificationsOf(skip.task().ownerUserId())).as("놓친 발화의 알림").isEmpty();
        Instant nextDay = Instant.parse("2026-11-05T00:00:00Z");
        assertThat(triggers.findById(runOnce.trigger().id()).orElseThrow().nextFireAt())
                .isEqualTo(nextDay);
        assertThat(triggers.findById(skip.trigger().id()).orElseThrow().nextFireAt())
                .isEqualTo(nextDay);
    }

    @Test
    @DisplayName("멈춘 작업은 예정 시각이 지나도 발화하지 않는다")
    void doesNotFirePausedTask() {
        Fixture fixture = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        Task task = tasks.findById(fixture.task().id()).orElseThrow();
        task.pause(NOW);
        tasks.save(task);

        int created = firing.fireDue(NOW);

        assertThat(created).isZero();
        assertThat(runsOf(fixture.task())).isEmpty();
        assertThat(triggers.findById(fixture.trigger().id()).orElseThrow().nextFireAt())
                .isEqualTo(NOVEMBER_FIRST);
    }

    @Test
    @DisplayName("그 사용자의 24시간 안 발화가 48개면 DAILY_LIMIT 로 건너뛰고 그 작업을 가리키는 TASK_SKIPPED 를 알린다")
    void skipsWithDailyLimitAndNotifies() {
        Fixture fixture = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        fillRecentRuns(fixture, 48);

        firing.fireDue(NOW);

        TaskRun fired = runs.findAll().stream()
                .filter(run -> run.scheduledFor().equals(NOVEMBER_FIRST))
                .filter(run -> run.taskId().equals(fixture.task().id()))
                .findFirst()
                .orElseThrow();
        assertThat(fired.status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(fired.reason()).isEqualTo(TaskRunReason.DAILY_LIMIT);
        assertThat(notificationsOf(fixture.task().ownerUserId()))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.kind()).isEqualTo(NotificationKind.TASK_SKIPPED);
                    assertThat(notification.title()).isEqualTo("「매달 정리」 실행을 건너뛰었어요");
                    assertThat(notification.body()).isEqualTo("하루 실행 횟수를 다 썼어요");
                    assertThat(notification.targetType()).isEqualTo(NotificationTargetType.TASK);
                    assertThat(notification.targetPublicId())
                            .isEqualTo(fixture.task().publicId());
                });
        assertThat(triggers.findById(fixture.trigger().id()).orElseThrow().nextFireAt())
                .isEqualTo(DECEMBER_FIRST);
    }

    @Test
    @DisplayName("24시간 안 발화가 47개면 상한 전이라 QUEUED 를 만든다")
    void firesBelowDailyLimit() {
        Fixture fixture = monthly(MissedPolicy.RUN_ONCE, NOVEMBER_FIRST);
        fillRecentRuns(fixture, 47);

        firing.fireDue(NOW);

        TaskRun fired = runs.findAll().stream()
                .filter(run -> run.scheduledFor().equals(NOVEMBER_FIRST))
                .filter(run -> run.taskId().equals(fixture.task().id()))
                .findFirst()
                .orElseThrow();
        assertThat(fired.status()).isEqualTo(TaskRunStatus.QUEUED);
        assertThat(notificationsOf(fixture.task().ownerUserId())).isEmpty();
    }

    @Test
    @DisplayName("한 번 도는 작업은 발화한 뒤 다음 예정 시각이 비고 다시 발화하지 않는다")
    void clearsNextFireAtAfterOnce() {
        AppUser owner = owner();
        Agent agent = agentOf(owner);
        Task task = tasks.save(task(owner, agent, NotifyPolicy.ALWAYS));
        TaskTrigger trigger =
                triggers.save(TaskTrigger.once(task.id(), NOVEMBER_FIRST, SEOUL, MissedPolicy.RUN_ONCE, NOW));

        firing.fireDue(NOW);
        firing.fireDue(NOW.plus(Duration.ofDays(1)));

        assertThat(runsOf(task))
                .singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TaskRunStatus.QUEUED));
        TaskTrigger stored = triggers.findById(trigger.id()).orElseThrow();
        assertThat(stored.nextFireAt()).as("다음 예정 시각").isNull();
        assertThat(stored.lastFiredAt()).isEqualTo(NOVEMBER_FIRST);
    }

    /** 그 작업의 주인이 지금 앞 1시간에 만든 발화 {@code count} 개를 넣는다. 예정 시각은 서로 다르다. */
    private void fillRecentRuns(Fixture fixture, int count) {
        for (int i = 0; i < count; i++) {
            runs.save(TaskRun.queued(
                    fixture.task().id(),
                    fixture.trigger().id(),
                    fixture.task().ownerUserId(),
                    NOVEMBER_FIRST.minus(Duration.ofDays(i + 1L)),
                    NOW.minus(Duration.ofHours(1))));
        }
    }

    private Fixture monthly(MissedPolicy missed, Instant nextFireAt) {
        AppUser owner = owner();
        Agent agent = agentOf(owner);
        Task task = tasks.save(task(owner, agent, NotifyPolicy.ALWAYS));
        TaskTrigger trigger = triggers.save(TaskTrigger.cron(task.id(), "0 9 1 * *", SEOUL, missed, nextFireAt, NOW));
        return new Fixture(task, trigger);
    }

    /** 매일 서울 9시 작업이다. 다음 예정 시각은 11월 1일에 멈춰 있다. */
    private Fixture daily(MissedPolicy missed) {
        AppUser owner = owner();
        Agent agent = agentOf(owner);
        Task task = tasks.save(task(owner, agent, NotifyPolicy.ALWAYS));
        TaskTrigger trigger =
                triggers.save(TaskTrigger.cron(task.id(), "0 9 * * *", SEOUL, missed, NOVEMBER_FIRST, NOW));
        return new Fixture(task, trigger);
    }

    private static Task task(AppUser owner, Agent agent, NotifyPolicy notify) {
        return Task.create(
                owner.id(), agent.id(), "매달 정리", "지난달 기록을 보고 정리해 줘", ConversationMode.NEW_PER_RUN, notify, NOW);
    }

    private AppUser owner() {
        String email = "firing-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return user;
    }

    private Agent agentOf(AppUser owner) {
        String code = "firing-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
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
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
        createdUsers.forEach(userId -> notifications.deleteAll(notificationsOf(userId)));
        createdUsers.clear();
    }

    private record Fixture(Task task, TaskTrigger trigger) {}
}
