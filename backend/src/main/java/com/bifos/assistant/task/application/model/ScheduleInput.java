package com.bifos.assistant.task.application.model;

import com.bifos.assistant.task.domain.type.TriggerType;
import java.time.LocalDateTime;

/**
 * 작업을 만들거나 고칠 때 받는 시각이다.
 *
 * @param cron {@code CRON} 일 때 5필드 cron
 * @param fireAt {@code ONCE} 일 때 시간대 없는 날짜와 시각
 * @param timeZone IANA 시간대 이름. 비면 기본 시간대다
 */
public record ScheduleInput(TriggerType type, String cron, LocalDateTime fireAt, String timeZone) {}
