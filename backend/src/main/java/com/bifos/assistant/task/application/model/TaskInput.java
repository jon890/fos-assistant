package com.bifos.assistant.task.application.model;

import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;

/**
 * 작업을 만들거나 고칠 때 받는 값이다. 뒤의 셋은 비면 기본값이다.
 *
 * @param title 앞뒤 공백을 떼고 저장한다
 * @param notifyPolicy 알림 설정. {@code Object.notify()} 와 겹치지 않게 이름을 달리한다
 */
public record TaskInput(
        String title,
        String agentCode,
        String instruction,
        ScheduleInput schedule,
        ConversationMode conversationMode,
        MissedPolicy missedPolicy,
        NotifyPolicy notifyPolicy) {}
