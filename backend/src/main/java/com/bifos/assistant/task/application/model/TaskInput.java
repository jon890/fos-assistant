package com.bifos.assistant.task.application.model;

import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;

/**
 * 작업을 만들거나 고칠 때 받는 값이다. {@code conversationMode}, {@code missedPolicy}, {@code notifyPolicy} 는 비면 기본값이다.
 *
 * @param title 앞뒤 공백을 떼고 저장한다
 * @param notifyPolicy 알림 설정. {@code Object.notify()} 와 겹치지 않게 이름을 달리한다
 * @param modelTier 발화하는 대화를 고를 모델 단계. 비우면 작업의 단계를 지운다
 */
public record TaskInput(
        String title,
        String agentCode,
        String instruction,
        ScheduleInput schedule,
        ConversationMode conversationMode,
        MissedPolicy missedPolicy,
        NotifyPolicy notifyPolicy,
        ModelTier modelTier) {}
