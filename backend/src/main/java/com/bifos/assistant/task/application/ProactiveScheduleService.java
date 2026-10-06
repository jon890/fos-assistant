package com.bifos.assistant.task.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.ProactiveCheckReadiness;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.task.application.model.ProactiveSchedule;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 에이전트 하나의 매일 먼저 살펴보기 깨우기 설정을 관리한다. */
@Service
@RequiredArgsConstructor
public class ProactiveScheduleService {

    private static final String DEFAULT_TIME = "09:00";
    private static final String CHECK_TITLE = "매일 먼저 살펴보기";
    private static final List<String> ISOLATION_REQUIRED_TOOLSETS = List.of("terminal", "file", "code_execution");

    private final AgentService agents;
    private final ProactiveCheckReadiness readiness;
    private final HermesToolsetClient toolsets;
    private final ProactiveScheduleProperties properties;
    private final TaskRepository tasks;
    private final TaskTriggerRepository triggers;
    private final ProactiveCheckRepository checks;
    private final TaskProperties taskProperties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ProactiveSchedule get(CurrentUser user, String agentCode) {
        Agent agent = agents.requireReadable(user, agentCode);
        Task task = tasks.findByOwnerUserIdAndAgentIdAndKind(user.id(), agent.id(), TaskKind.CHECK)
                .orElse(null);
        TaskTrigger trigger =
                task == null ? null : triggers.findByTaskId(task.id()).orElse(null);
        return view(user, agent, task, trigger, displayBlockers(agent));
    }

    /** 발화 직전에 현재 toolset과 격리 실행 공간 조건을 다시 확인한다. */
    @Transactional(readOnly = true)
    public boolean schedulingAvailable(CurrentUser user, String agentCode) {
        return blockers(agents.requireStartable(user, agentCode)).isEmpty();
    }

    @Transactional
    public ProactiveSchedule update(CurrentUser user, String agentCode, boolean enabled, String time, String timeZone) {
        Agent agent = enabled ? agents.requireStartable(user, agentCode) : agents.requireReadable(user, agentCode);
        ZoneId zone = TaskSchedule.parseZone(requiredTimeZone(timeZone));
        LocalTime localTime = parseTime(time);
        Task task = tasks.findByOwnerUserIdAndAgentIdAndKindForUpdate(user.id(), agent.id(), TaskKind.CHECK)
                .orElse(null);
        Instant now = clock.instant();
        String cron = cron(localTime);
        Instant next = TaskSchedule.nextAfter(TaskSchedule.parseCron(cron), zone, now);
        List<CheckBlocker> checkedBlockers = enabled ? blockers(agent) : unknownReadiness();
        if (enabled && !checkedBlockers.isEmpty()) {
            throw new ApiException(
                    ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "this agent cannot schedule a proactive check now");
        }
        if (task == null) {
            task = tasks.save(Task.check(user.id(), agent.id(), CHECK_TITLE, now));
            triggers.save(TaskTrigger.cron(task.id(), cron, zone, MissedPolicy.SKIP, next, now));
        } else {
            TaskTrigger trigger = requireTrigger(task);
            trigger.reschedule(TriggerType.CRON, cron, null, zone, next, now);
            trigger.changeMissedPolicy(MissedPolicy.SKIP, now);
        }
        if (enabled) {
            task.changeCheckEnabled(true, now);
        } else {
            task.changeCheckEnabled(false, now);
        }
        TaskTrigger trigger = task == null ? null : requireTrigger(task);
        return view(user, agent, task, trigger, checkedBlockers);
    }

    private ProactiveSchedule view(
            CurrentUser user, Agent agent, Task task, TaskTrigger trigger, List<CheckBlocker> blockers) {
        ProactiveCheck last = checks.findFirstByUserIdAndAgentIdOrderByIdDesc(user.id(), agent.id())
                .orElse(null);
        return new ProactiveSchedule(
                task != null && task.state() == TaskState.ACTIVE,
                trigger == null ? DEFAULT_TIME : timeOf(trigger),
                trigger == null ? taskProperties.defaultZone().getId() : trigger.timeZone(),
                task == null || task.state() != TaskState.ACTIVE || trigger == null ? null : trigger.nextFireAt(),
                last == null
                        ? null
                        : new ProactiveSchedule.LastCheck(
                                last.status(),
                                last.outcome(),
                                last.invalidReason(),
                                last.skippedReason(),
                                last.startedAt(),
                                last.finishedAt()),
                blockers.isEmpty(),
                blockers);
    }

    /** 저장된 설정은 Hermes 장애 중에도 읽도록 준비 상태 조회 실패만 화면용 차단 사유로 바꾼다. */
    private List<CheckBlocker> displayBlockers(Agent agent) {
        try {
            return blockers(agent);
        } catch (ApiException ex) {
            boolean runtimeUnavailable = ex.code() == ErrorCode.HERMES_UNAVAILABLE
                    || ex.code() == ErrorCode.HERMES_BUSY
                    || ex.code() == ErrorCode.HERMES_PROFILE_KEY_MISSING;
            if (!runtimeUnavailable) {
                throw ex;
            }
            return unknownReadiness();
        }
    }

    private static List<CheckBlocker> unknownReadiness() {
        return List.of(CheckBlocker.of(CheckBlockerCode.READINESS_UNKNOWN));
    }

    private List<CheckBlocker> blockers(Agent agent) {
        CheckReadiness checked = readiness.check(agent);
        List<CheckBlocker> blockers = new ArrayList<>(checked.blockers());
        boolean unsupported =
                blockers.stream().anyMatch(blocker -> blocker.code() == CheckBlockerCode.AGENT_NOT_SUPPORTED);
        if (unsupported) {
            return List.copyOf(blockers);
        }
        if (!properties.isolatedExecutionEnabled()) {
            List<String> unsafe = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).stream()
                    .filter(ISOLATION_REQUIRED_TOOLSETS::contains)
                    .sorted()
                    .toList();
            if (!unsafe.isEmpty()) {
                blockers.add(new CheckBlocker(CheckBlockerCode.ISOLATED_EXECUTION_REQUIRED, unsafe));
            }
        }
        return List.copyOf(blockers);
    }

    private TaskTrigger requireTrigger(Task task) {
        return triggers.findByTaskId(task.id())
                .orElseThrow(() -> new IllegalStateException("proactive schedule task has no trigger"));
    }

    private static String requiredTimeZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new ApiException(ErrorCode.TASK_SCHEDULE_INVALID, "a time zone is required");
        }
        return timeZone.strip();
    }

    private static LocalTime parseTime(String time) {
        if (time == null || !time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "time must use HH:mm");
        }
        return LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME);
    }

    private static String cron(LocalTime time) {
        return time.getMinute() + " " + time.getHour() + " * * *";
    }

    private static String timeOf(TaskTrigger trigger) {
        String[] fields = trigger.cronExpr().split("\\s+");
        return String.format("%02d:%02d", Integer.parseInt(fields[1]), Integer.parseInt(fields[0]));
    }
}
