package com.bifos.assistant.task.application;

import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.application.ControlPlaneApprovals;
import com.bifos.assistant.mcp.application.dto.TaskToolInput;
import com.bifos.assistant.mcp.application.dto.TaskToolResult;
import com.bifos.assistant.mcp.application.port.TaskRequests;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.dto.ScheduleInput;
import com.bifos.assistant.task.application.dto.TaskDetail;
import com.bifos.assistant.task.application.dto.TaskInput;
import com.bifos.assistant.task.application.dto.TaskTarget;
import com.bifos.assistant.task.config.TaskProperties;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 에이전트가 대화에서 요청자의 예약 작업을 다루는 길이다(스펙 service-concepts REQ7-REQ10). 계약은
 * {@code docs/features/service-concepts/design.md} 의 「PR2 예약 작업 도구」 가 갖는다.
 *
 * <p>만들기와 고치기는 화면의 {@link TaskService} 를 그대로 불러 같은 검사(작업 수, 최소 간격, 길이)를 받는다. 실패는 예외가 아니라
 * 까닭이 담긴 결과로 돌려준다. 이 클래스에는 트랜잭션을 두지 않는다. {@link TaskService} 가 예외로 끝나면 그 트랜잭션만 되돌린다.
 */
@Component
@RequiredArgsConstructor
public class TaskAgentRequests implements TaskRequests {
    /** 하루 발화 수를 셀 앞날이다. 요일 주기 하나를 다 본다. */
    private static final Duration DAILY_LIMIT_HORIZON = Duration.ofDays(8);
    private final TaskService tasks;
    private final TaskAgentLookup lookup;
    private final AgentExecutionRepository executions;
    private final ControlPlaneApprovals approvals;
    private final TaskProperties properties;
    private final Clock clock;


    @Override
    public TaskToolResult list(CurrentUser user) {
        return TaskToolResult.ok(Map.of("status", "ok", "tasks", lookup.list(user)));
    }


    /**
     * 만든다. 에이전트는 요청자의 기본 비서다(비서 중심 대화 스펙 REQ7). 맡긴 실행이 아니고 대화가 있으면 결과를 그 대화에
     * 쌓는다(스펙 DECISION27, DECISION29). 그 대화의 에이전트가 기본 비서가 아니면 발화 때 새 대화가 열린다. 모델 단계는 비우고
     * 시간대와 놓친 실행 처리는 화면 기본값이다(스펙 DECISION12).
     */
    @Override
    public TaskToolResult create(CurrentUser user, Long originExecutionId, TaskToolInput input) {
        Optional<AgentExecution> origin = executions.findById(originExecutionId);
        if (origin.isEmpty()) {
            return TaskToolResult.failed("agent_unavailable", "이 실행을 찾지 못해 만들지 않았다. /tasks/new 화면에서 만들라고 안내한다.");
        }
        Long conversationId = origin.get().parentExecutionId() == null ? origin.get().conversationId() : null;
        Optional<UUID> same = lookup.sameTask(user, input.title(), input.cron(), input.fireAt());
        if (same.isPresent()) {
            // 같은 호출을 다시 받아도 두 번 만들지 않는다. 이름과 시각이 같은 작업이 이미 있으면 그것을 알린다.
            return done("exists", user, same.get());
        }
        try {
            ScheduleInput schedule = schedule(input.cron(), input.fireAt(), properties.defaultZone());
            Optional<TaskToolResult> tooMany = dailyLimit(schedule);
            if (tooMany.isPresent()) {
                return tooMany.get();
            }
            TaskInput created = new TaskInput(input.title(), input.instruction(), schedule,
                conversationId == null ? ConversationMode.NEW_PER_RUN : ConversationMode.SINGLE, null, notifyOf(input.notifyPolicy()), null);
            TaskDetail detail = tasks.create(user, created, conversationId);
            return done("created", user, detail.task().publicId());
        } catch (ApiException ex) {
            return failure(ex);
        }
    }


    /**
     * 받은 칸만 바꾼다. 모델 단계, 시간대, 대화 방식, 놓친 실행 처리는 저장된 값 그대로다(스펙 DECISION23). 에이전트는 화면에서
     * 고칠 때와 같게 기본 비서로 바뀐다(비서 중심 대화 스펙 DECISION23).
     */
    @Override
    public TaskToolResult update(CurrentUser user, String ref, TaskToolInput changes) {
        return withTarget(user, ref, taskId -> {
            TaskDetail current = tasks.get(user, taskId);
            Task task = current.task();
            ScheduleInput schedule = changes.cron() == null && changes.fireAt() == null
                ? stored(task)
                : schedule(changes.cron(), changes.fireAt(), task.zone());
            if (changes.cron() != null || changes.fireAt() != null) {
                Optional<TaskToolResult> tooMany = dailyLimit(schedule);
                if (tooMany.isPresent()) {
                    return tooMany.get();
                }
            }
            TaskInput edited = new TaskInput(
                changes.title() == null ? task.title() : changes.title(),
                changes.instruction() == null ? task.instruction() : changes.instruction(),
                schedule,
                task.conversationMode(),
                task.missedPolicy(),
                changes.notifyPolicy() == null ? task.notifyPolicy() : notifyOf(changes.notifyPolicy()),
                task.modelTier());
            tasks.update(user, taskId, edited);
            return done("updated", user, taskId);
        });
    }


    @Override
    public TaskToolResult setPaused(CurrentUser user, String ref, boolean paused) {
        return withTarget(user, ref, taskId -> {
            if (paused) {
                tasks.pause(user, taskId);
            } else {
                tasks.resume(user, taskId);
            }
            return done(paused ? "paused" : "active", user, taskId);
        });
    }


    /** 지우지 않고 승인 카드를 띄운다. 카드에는 작업 이름과 다음 실행 시각이 보인다(스펙 REQ9). */
    @Override
    public TaskToolResult requestDelete(CurrentUser user, Long originExecutionId, String ref) {
        return withTarget(user, ref, taskId -> {
            Map<String, Object> summary = lookup.summary(user, taskId);
            if (summary == null) {
                return TaskToolResult.failed("not_found", "그런 예약 작업이 없다. task_list 로 확인한다.");
            }
            Map<String, String> args = new LinkedHashMap<>();
            args.put("task", String.valueOf(summary.get("title")));
            args.put("next_run", summary.get("next_run") == null ? "없음" : String.valueOf(summary.get("next_run")));
            args.put("task_id", taskId.toString());
            var request = approvals.request(user, originExecutionId, ControlPlaneActions.TASK_DELETE, taskId.toString(), args);
            if (request.status() != ActionStatus.PENDING) {
                return TaskToolResult.failed("closed", "같은 요청의 승인 카드가 이미 끝났다(" + request.status().name()
                    + "). 작업이 지워졌는지는 task_list 로 확인한다. 다시 지우려면 사용자에게 확인한 뒤 사용자의 다음 요청에서 부른다.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "approval_requested");
            body.put("task", summary);
            body.put("message", "지우기 승인 카드를 띄웠다. 아직 지우지 않았다. 사용자가 카드에서 승인해야 지워진다.");
            return TaskToolResult.ok(body);
        });
    }


    private TaskToolResult withTarget(CurrentUser user, String ref, Function<UUID, TaskToolResult> action) {
        TaskTarget found = lookup.find(user, ref);
        if (found.taskId() == null) {
            if (found.candidates().isEmpty()) {
                return TaskToolResult.failed("not_found", "그런 예약 작업이 없다. task_list 로 이름을 확인하거나 사용자에게 되묻는다.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ambiguous");
            body.put("message", "이름이 같은 작업이 여럿이라 바꾸지 않았다. 어느 작업인지 사용자에게 되묻고 그 id 로 다시 부른다.");
            body.put("candidates", found.candidates());
            return new TaskToolResult(true, body);
        }
        try {
            return action.apply(found.taskId());
        } catch (ApiException ex) {
            return failure(ex);
        }
    }


    private TaskToolResult done(String status, CurrentUser user, UUID taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("task", lookup.summary(user, taskId));
        return TaskToolResult.ok(body);
    }


    /** 도구가 준 시각을 화면의 시각 입력으로 바꾼다. {@code fire_at} 의 꼴이 틀리면 시각 오류다. */
    private static ScheduleInput schedule(String cron, String fireAt, ZoneId zone) {
        if (cron != null) {
            return new ScheduleInput(TriggerType.CRON, cron, null, zone.getId());
        }
        try {
            return new ScheduleInput(TriggerType.ONCE, null, LocalDateTime.parse(fireAt), zone.getId());
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "fire_at is not a local date time");
        }
    }


    private static ScheduleInput stored(Task task) {
        LocalDateTime fireAt = task.fireAt() == null ? null : LocalDateTime.ofInstant(task.fireAt(), task.zone());
        return new ScheduleInput(task.type(), task.cronExpr(), fireAt, task.timeZone());
    }


    /**
     * cron 이 앞으로 7일 가운데 어느 하루(작업 시간대의 달력 하루)라도 하루 발화 수 상한보다 많이 돌면 거절한다(설계 DECISION10). 식이 틀렸으면 여기서 보지 않고
     * {@link TaskService} 의 검사에 맡긴다.
     */
    private Optional<TaskToolResult> dailyLimit(ScheduleInput schedule) {
        if (schedule.type() != TriggerType.CRON) {
            return Optional.empty();
        }
        CronExpression cron;
        try {
            cron = TaskSchedule.parseCron(schedule.cron().strip());
        } catch (ApiException ex) {
            return Optional.empty();
        }
        ZoneId zone = ZoneId.of(schedule.timeZone());
        Instant now = clock.instant();
        // 최소 간격보다 촘촘한 식은 한도가 아니라 시각 오류로 알린다. 화면과 같은 까닭이다.
        TaskSchedule.requireMinInterval(cron, zone, now, properties.minInterval());
        // 요일이나 날짜를 고른 식이 지금부터 24시간만 세면 빠져나간다. 앞으로 7일의 달력 하루(작업 시간대)마다 센다.
        Instant end = now.plus(DAILY_LIMIT_HORIZON);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        Instant next = TaskSchedule.nextAfter(cron, zone, now);
        while (next != null && next.isBefore(end)) {
            int fires = perDay.merge(next.atZone(zone).toLocalDate(), 1, Integer::sum);
            if (fires > properties.maxRunsPerDay()) {
                return Optional.of(TaskToolResult.failed("daily_limit", "이 주기는 하루 " + properties.maxRunsPerDay()
                    + "번 발화 한도를 넘어 만들지 않았다. 간격을 넓히자고 사용자에게 제안한다."));
            }
            next = TaskSchedule.nextAfter(cron, zone, next);
        }
        return Optional.empty();
    }


    private static NotifyPolicy notifyOf(String value) {
        return value == null ? null : NotifyPolicy.valueOf(value.toUpperCase(Locale.ROOT));
    }


    /** 화면이 내는 오류를 도구의 까닭으로 바꾼다. 모르는 오류는 그대로 내 도구가 일시 장애로 답한다. */
    private static TaskToolResult failure(ApiException ex) {
        return switch (ex.code()) {
            case TASK_NOT_FOUND -> TaskToolResult.failed("not_found", "그런 예약 작업이 없다. task_list 로 확인한다.");
            case TASK_LIMIT_REACHED -> TaskToolResult.failed("task_limit",
                "예약 작업이 이미 상한만큼 있어 만들지 않았다. /tasks 에서 쓰지 않는 작업을 지우라고 안내한다.");
            case TASK_SCHEDULE_INVALID -> TaskToolResult.failed("invalid_schedule",
                "시각이 올바르지 않거나 반복 간격이 15분보다 짧거나 이미 지난 시각이라 하지 않았다. 시각을 다시 확인한다.");
            case VALIDATION_FAILED -> TaskToolResult.failed("invalid", "이름은 1자부터 100자까지, 지시는 8000자까지다.");
            case SKILL_COMMAND_UNKNOWN -> TaskToolResult.failed("unknown_skill",
                "지시 맨 앞의 /스킬을 기본 비서가 쓸 수 없어 하지 않았다. 지시를 그 스킬 없이 고쳐 다시 부른다.");
            case AGENT_NOT_FOUND, AGENT_DISABLED -> TaskToolResult.failed("agent_unavailable",
                "기본 비서를 쓸 수 없어 하지 않았다. 관리자에게 알리라고 안내한다.");
            default -> throw ex;
        };
    }
}
