package com.bifos.assistant.task.presentation;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.task.application.model.ProactiveSchedule;
import com.bifos.assistant.task.application.model.ProactiveSchedule.LastCheck;
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
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 예약 작업 경로의 요청과 응답 모양이다. 계약은 {@code docs/features/schedule.md} 의 「API(예약 작업)」 가 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskDtos {

    /** 매일 깨우기를 켜거나 끄는 요청이다. */
    public record ProactiveScheduleRequest(
            boolean enabled,
            @NotBlank String time,
            @NotBlank String timezone) {}

    /** 에이전트별 매일 깨우기 설정이다. */
    public record ProactiveScheduleView(
            boolean enabled,
            String time,
            String timezone,
            Instant nextRunAt,
            LastCheckView lastCheck,
            boolean schedulingAvailable,
            List<ProactiveBlockerView> blockers) {

        public static ProactiveScheduleView from(ProactiveSchedule schedule) {
            return new ProactiveScheduleView(
                    schedule.enabled(),
                    schedule.time(),
                    schedule.timeZone(),
                    schedule.nextRunAt(),
                    schedule.lastCheck() == null ? null : LastCheckView.from(schedule.lastCheck()),
                    schedule.schedulingAvailable(),
                    schedule.blockers().stream().map(ProactiveBlockerView::from).toList());
        }
    }

    /** 매일 깨우기를 막는 까닭이다. */
    public record ProactiveBlockerView(String code, List<String> toolsets) {

        static ProactiveBlockerView from(CheckBlocker blocker) {
            return new ProactiveBlockerView(blocker.code().name(), blocker.toolsets());
        }
    }

    /** 매일 깨우기가 마지막으로 연 살펴보기다. */
    public record LastCheckView(
            String status,
            String outcome,
            String invalidReason,
            String skippedReason,
            Instant startedAt,
            Instant finishedAt) {

        static LastCheckView from(LastCheck check) {
            return new LastCheckView(
                    check.status().name(),
                    check.outcome() == null ? null : check.outcome().name(),
                    check.invalidReason() == null ? null : check.invalidReason().name(),
                    check.skippedReason() == null ? null : check.skippedReason().name(),
                    check.startedAt(),
                    check.finishedAt());
        }
    }

    /**
     * 작업을 만들거나 고치는 요청이다. {@code conversationMode}, {@code missedPolicy}, {@code notify} 는 비우면 기본값이다.
     *
     * @param title 앞뒤 공백을 떼고 저장한다
     * @param notifyPolicy JSON 이름은 {@code notify} 다. {@code Object.notify()} 와 겹쳐 record 이름을 달리한다
     * @param modelTier 발화하는 대화를 고를 모델 단계. 비우면 작업의 단계를 지운다
     */
    public record TaskRequest(
            @NotBlank @Size(max = Task.TITLE_MAX) String title,
            @NotBlank String agentCode,
            @NotBlank @Size(max = Task.INSTRUCTION_MAX) String instruction,
            @NotNull @Valid ScheduleRequest schedule,
            ConversationMode conversationMode,
            MissedPolicy missedPolicy,
            @JsonProperty("notify") NotifyPolicy notifyPolicy,
            ModelTier modelTier) {

        public TaskInput toInput() {
            return new TaskInput(
                    title,
                    agentCode,
                    instruction,
                    schedule.toInput(),
                    conversationMode,
                    missedPolicy,
                    notifyPolicy,
                    modelTier);
        }
    }

    /**
     * 작업의 시각이다. {@code CRON} 은 {@code cron} 을, {@code ONCE} 는 {@code fireAt} 을 채운다.
     *
     * @param fireAt 시간대 없는 날짜와 시각. {@code timeZone} 으로 해석한다
     * @param timeZone IANA 시간대 이름. 비우면 기본 시간대다
     */
    public record ScheduleRequest(@NotNull TriggerType type, String cron, LocalDateTime fireAt, String timeZone) {

        ScheduleInput toInput() {
            return new ScheduleInput(type, cron, fireAt, timeZone);
        }
    }

    /**
     * 저장된 시각이다. 요청과 같은 모양이다.
     *
     * @param fireAt 저장한 UTC 시각을 그 작업의 시간대로 바꾼 값. {@code CRON} 이면 null
     */
    public record ScheduleView(TriggerType type, String cron, LocalDateTime fireAt, String timeZone) {

        static ScheduleView from(TaskTrigger trigger) {
            return new ScheduleView(
                    trigger.type(),
                    trigger.cronExpr(),
                    trigger.fireAt() == null ? null : LocalDateTime.ofInstant(trigger.fireAt(), trigger.zone()),
                    trigger.timeZone());
        }
    }

    /**
     * 작업 한 줄이다.
     *
     * @param id 작업의 공개 식별자
     * @param agentCode 에이전트 행이 없으면 null
     * @param agentName 에이전트 행이 없으면 null
     * @param nextFireAt 다음 예정 시각. 없으면 null
     * @param lastFiredAt 마지막으로 처리한 예정 시각. 없으면 null
     * @param notifyPolicy JSON 이름은 {@code notify} 다
     * @param modelTier 발화하는 대화를 고를 모델 단계. 비었으면 null
     */
    public record TaskView(
            UUID id,
            String title,
            String agentCode,
            String agentName,
            String instruction,
            TaskState state,
            ScheduleView schedule,
            Instant nextFireAt,
            Instant lastFiredAt,
            ConversationMode conversationMode,
            MissedPolicy missedPolicy,
            @JsonProperty("notify") NotifyPolicy notifyPolicy,
            ModelTier modelTier,
            Instant createdAt) {

        public static TaskView from(TaskDetail detail) {
            Task task = detail.task();
            TaskTrigger trigger = detail.trigger();
            Agent agent = detail.agent();
            return new TaskView(
                    task.publicId(),
                    task.title(),
                    agent == null ? null : agent.code(),
                    agent == null ? null : agent.name(),
                    task.instruction(),
                    task.state(),
                    ScheduleView.from(trigger),
                    trigger.nextFireAt(),
                    trigger.lastFiredAt(),
                    task.conversationMode(),
                    trigger.missedPolicy(),
                    task.notifyPolicy(),
                    task.modelTier(),
                    task.createdAt());
        }
    }

    /**
     * 발화 한 번이다.
     *
     * @param id 발화의 공개 식별자
     * @param reason {@code SKIPPED} 와 {@code FAILED} 의 까닭. enum 이름 그대로 싣고 화면이 문구로 바꾼다. 없으면 null
     * @param conversationId 결과를 남긴 대화의 공개 식별자. 없으면 null
     */
    public record TaskRunView(
            UUID id,
            Instant scheduledFor,
            TaskRunStatus status,
            String reason,
            UUID conversationId,
            Instant startedAt,
            Instant finishedAt) {

        public static TaskRunView from(TaskRunDetail detail) {
            TaskRun run = detail.run();
            return new TaskRunView(
                    run.publicId(),
                    run.scheduledFor(),
                    run.status(),
                    run.reason() == null ? null : run.reason().name(),
                    detail.conversationId(),
                    run.startedAt(),
                    run.finishedAt());
        }
    }
}
