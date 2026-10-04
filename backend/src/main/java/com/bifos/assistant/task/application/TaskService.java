package com.bifos.assistant.task.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ConversationPublicIdLookup;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.model.ScheduleInput;
import com.bifos.assistant.task.application.model.TaskDetail;
import com.bifos.assistant.task.application.model.TaskInput;
import com.bifos.assistant.task.application.model.TaskRunDetail;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 작업을 만들고 고치고 멈추고 지운다(ADR-076, ADR-079).
 *
 * <p>계약은 {@code docs/backend/task.md} 의 「작업」, 「시각」, 「API」 가 갖는다. 로그인한 사용자 자신의 작업만 다룬다. 남의
 * 작업, 없는 작업, 지운 작업은 같은 {@code TASK_NOT_FOUND} 다. 모든 시각은 주입받은 {@link Clock} 에서 나온다.
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 발화 기록 한 번에 읽는 상한이다. */
    public static final int MAX_RUNS = 100;

    private static final String NOT_FOUND_MESSAGE = "this task does not exist";

    private final TaskRepository tasks;
    private final TaskTriggerRepository triggers;
    private final TaskRunRepository runs;
    private final AppUserRepository users;
    private final AgentService agents;
    private final KnownFlows flows;
    private final ConversationPublicIdLookup conversations;
    private final TaskProperties properties;
    private final Clock clock;

    /**
     * 작업과 그 시각을 한 트랜잭션에 저장한다.
     *
     * <p>주인 행을 잠그고 센다. 동시에 두 요청이 와도 보관하지 않은 작업이 상한을 넘지 않는다.
     */
    @Transactional
    public TaskDetail create(CurrentUser user, TaskInput input) {
        Instant now = clock.instant();
        users.findByIdForUpdate(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
        if (tasks.countByOwnerUserIdAndStateNot(user.id(), TaskState.ARCHIVED) >= properties.maxPerUser()) {
            throw new ApiException(
                    ErrorCode.TASK_LIMIT_REACHED, "a task limit of " + properties.maxPerUser() + " was reached");
        }
        Agent agent = requireAgent(user, input.agentCode());
        Schedule schedule = validated(input.schedule(), now);
        Task task = tasks.save(Task.create(
                user.id(), agent.id(), title(input.title()), input.instruction(), modeOf(input), notifyOf(input), now));
        MissedPolicy missed = missedOf(input);
        TaskTrigger trigger = triggers.save(
                schedule.type() == TriggerType.CRON
                        ? TaskTrigger.cron(task.id(), schedule.cron(), schedule.zone(), missed, schedule.next(), now)
                        : TaskTrigger.once(task.id(), schedule.fireAt(), schedule.zone(), missed, now));
        return new TaskDetail(task, trigger, agent);
    }

    /**
     * 작업을 고친다. 작업 수 상한은 보지 않는다.
     *
     * <p>시각(종류, cron, {@code fireAt}, 시간대)이 저장된 값과 다를 때만 시각을 검사하고 다음 예정 시각을 다시 계산한다. 그래서
     * 이미 발화한 {@code ONCE} 작업도 같은 시각을 보내며 이름을 고칠 수 있다.
     */
    @Transactional
    public TaskDetail update(CurrentUser user, UUID taskId, TaskInput input) {
        Instant now = clock.instant();
        Task task = requireTask(user, taskId);
        TaskTrigger trigger = requireTrigger(task);
        Agent agent = requireAgent(user, input.agentCode());
        task.edit(agent.id(), title(input.title()), input.instruction(), modeOf(input), notifyOf(input), now);
        if (!sameSchedule(trigger, input.schedule())) {
            Schedule schedule = validated(input.schedule(), now);
            trigger.reschedule(
                    schedule.type(), schedule.cron(), schedule.fireAt(), schedule.zone(), schedule.next(), now);
        }
        MissedPolicy missed = missedOf(input);
        if (trigger.missedPolicy() != missed) {
            trigger.changeMissedPolicy(missed, now);
        }
        return new TaskDetail(task, trigger, agent);
    }

    /** 멈춘다. {@code ACTIVE} 가 아니면 그대로 돌려준다. */
    @Transactional
    public TaskDetail pause(CurrentUser user, UUID taskId) {
        Task task = requireTask(user, taskId);
        task.pause(clock.instant());
        return detailOf(task, requireTrigger(task));
    }

    /**
     * 다시 켠다. {@code PAUSED} 가 아니면 그대로 돌려준다.
     *
     * <p>다음 예정 시각을 지금 뒤의 첫 시각으로 다시 계산한다. 멈춘 동안의 시각은 놓친 발화로 세지 않는다. 이미 지난 {@code ONCE}
     * 는 다음 예정 시각이 빈 채로 남는다.
     */
    @Transactional
    public TaskDetail resume(CurrentUser user, UUID taskId) {
        Instant now = clock.instant();
        Task task = requireTask(user, taskId);
        TaskTrigger trigger = requireTrigger(task);
        if (task.resume(now)) {
            trigger.moveNext(nextOf(trigger, now), now);
        }
        return detailOf(task, trigger);
    }

    /** 지운다. 줄은 {@code ARCHIVED} 로 남는다. */
    @Transactional
    public void archive(CurrentUser user, UUID taskId) {
        requireTask(user, taskId).archive(clock.instant());
    }

    /** 내 보관하지 않은 작업을 만든 순서의 역순으로 읽는다. */
    @Transactional(readOnly = true)
    public List<TaskDetail> list(CurrentUser user) {
        List<Task> found = tasks.findByOwnerUserIdAndStateNotOrderByIdDesc(user.id(), TaskState.ARCHIVED);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, TaskTrigger> byTask = triggers
                .findByTaskIdIn(found.stream().map(Task::id).toList())
                .stream()
                .collect(Collectors.toMap(TaskTrigger::taskId, Function.identity()));
        Map<Long, Agent> byAgent =
                agents.byIds(found.stream().map(Task::agentId).distinct().toList());
        return found.stream()
                .map(task -> new TaskDetail(task, byTask.get(task.id()), byAgent.get(task.agentId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDetail get(CurrentUser user, UUID taskId) {
        Task task = requireTask(user, taskId);
        return detailOf(task, requireTrigger(task));
    }

    /**
     * 그 작업의 발화를 예정 시각의 역순으로 읽는다.
     *
     * @param limit 1 이상 {@link #MAX_RUNS} 이하. 범위 밖이면 {@code VALIDATION_FAILED}
     */
    @Transactional(readOnly = true)
    public List<TaskRunDetail> runs(CurrentUser user, UUID taskId, int limit) {
        if (limit < 1 || limit > MAX_RUNS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_RUNS);
        }
        Task task = requireTask(user, taskId);
        List<TaskRun> found = runs.findByTaskIdOrderByScheduledForDescIdDesc(task.id(), PageRequest.of(0, limit));
        Map<Long, UUID> publicIds = conversations.publicIdsOf(found.stream()
                .map(TaskRun::conversationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return found.stream()
                .map(run -> new TaskRunDetail(
                        run, run.conversationId() == null ? null : publicIds.get(run.conversationId())))
                .toList();
    }

    private Task requireTask(CurrentUser user, UUID taskId) {
        return tasks.findByPublicIdAndOwnerUserId(taskId, user.id())
                .filter(task -> !task.archived())
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, NOT_FOUND_MESSAGE));
    }

    private TaskTrigger requireTrigger(Task task) {
        return triggers.findByTaskId(task.id())
                .orElseThrow(() -> new IllegalStateException("task " + task.id() + " has no trigger"));
    }

    private TaskDetail detailOf(Task task, TaskTrigger trigger) {
        return new TaskDetail(task, trigger, agents.findById(task.agentId()).orElse(null));
    }

    /** 주인이 대화를 시작할 수 있는 에이전트여야 한다. 흐름이 붙은 에이전트는 받지 않는다. */
    private Agent requireAgent(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        if (flows.known(agent.flow())) {
            throw new ApiException(ErrorCode.TASK_AGENT_NOT_SUPPORTED, "an agent with a flow cannot run a task");
        }
        return agent;
    }

    /** 시각을 검사하고 다음 예정 시각을 계산한다. */
    private Schedule validated(ScheduleInput input, Instant now) {
        Schedule schedule = parsed(input);
        if (schedule.type() == TriggerType.ONCE) {
            Instant at = TaskSchedule.onceAt(input.fireAt(), schedule.zone(), now);
            return new Schedule(TriggerType.ONCE, null, at, schedule.zone(), at);
        }
        CronExpression cron = TaskSchedule.parseCron(schedule.cron());
        Instant next = TaskSchedule.nextAfter(cron, schedule.zone(), now);
        if (next == null) {
            throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "this cron expression never fires");
        }
        TaskSchedule.requireMinInterval(cron, schedule.zone(), now, properties.minInterval());
        return new Schedule(TriggerType.CRON, schedule.cron(), null, schedule.zone(), next);
    }

    /** 요청의 시각을 저장하는 모양으로 바꾼다. 지금과 견주는 검사는 하지 않는다. */
    private Schedule parsed(ScheduleInput input) {
        if (input == null || input.type() == null) {
            throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "a schedule type is required");
        }
        ZoneId zone = input.timeZone() == null || input.timeZone().isBlank()
                ? properties.defaultZone()
                : TaskSchedule.parseZone(input.timeZone().strip());
        if (input.type() == TriggerType.ONCE) {
            if (input.fireAt() == null) {
                throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "fireAt is required for ONCE");
            }
            return new Schedule(
                    TriggerType.ONCE, null, input.fireAt().atZone(zone).toInstant(), zone, null);
        }
        if (input.cron() == null || input.cron().isBlank()) {
            throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "cron is required for CRON");
        }
        return new Schedule(TriggerType.CRON, input.cron().strip(), null, zone, null);
    }

    /** 요청의 시각이 저장된 시각과 같은지 본다. 종류, cron, {@code fireAt}, 시간대를 견준다. */
    private boolean sameSchedule(TaskTrigger trigger, ScheduleInput input) {
        Schedule requested = parsed(input);
        if (requested.type() != trigger.type() || !requested.zone().getId().equals(trigger.timeZone())) {
            return false;
        }
        if (requested.type() == TriggerType.CRON) {
            return requested.cron().equals(trigger.cronExpr());
        }
        return requested.fireAt().truncatedTo(ChronoUnit.MICROS).equals(trigger.fireAt());
    }

    /** 지금 뒤의 첫 예정 시각이다. 지난 {@code ONCE} 와 다음 시각이 없는 cron 은 null 이다. */
    private static Instant nextOf(TaskTrigger trigger, Instant now) {
        if (trigger.type() == TriggerType.ONCE) {
            return trigger.fireAt().isAfter(now) ? trigger.fireAt() : null;
        }
        return TaskSchedule.nextAfter(TaskSchedule.parseCron(trigger.cronExpr()), trigger.zone(), now);
    }

    private static String title(String title) {
        String stripped = title == null ? "" : title.strip();
        if (stripped.isEmpty() || stripped.length() > Task.TITLE_MAX) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a task title must have 1 to " + Task.TITLE_MAX + " characters");
        }
        return stripped;
    }

    private static ConversationMode modeOf(TaskInput input) {
        return input.conversationMode() == null ? ConversationMode.NEW_PER_RUN : input.conversationMode();
    }

    private static MissedPolicy missedOf(TaskInput input) {
        return input.missedPolicy() == null ? MissedPolicy.RUN_ONCE : input.missedPolicy();
    }

    private static NotifyPolicy notifyOf(TaskInput input) {
        return input.notifyPolicy() == null ? NotifyPolicy.ALWAYS : input.notifyPolicy();
    }

    /**
     * 검사를 지난 시각이다.
     *
     * @param next 다음 예정 시각. 지금과 견주기 전이면 null
     */
    private record Schedule(TriggerType type, String cron, Instant fireAt, ZoneId zone, Instant next) {}
}
