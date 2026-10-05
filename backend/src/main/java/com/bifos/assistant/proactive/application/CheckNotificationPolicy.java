package com.bifos.assistant.proactive.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;

/** 예약 점검의 조용한 시간에는 승인 카드를 유지하고 알림만 생략한다. */
public interface CheckNotificationPolicy {

    boolean allows(AgentExecution origin, Instant now);
}
