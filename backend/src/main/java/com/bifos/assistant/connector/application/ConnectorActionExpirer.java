package com.bifos.assistant.connector.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 답이 없는 승인 요청을 만료로 바꾸는 일정이다(ADR-048). 무엇을 바꾸는지는 {@link ConnectorActionService#expire} 가 갖는다. */
@Component
@RequiredArgsConstructor
public class ConnectorActionExpirer {
    private final ConnectorActionService actions;
    private final Clock clock = Clock.systemUTC();

    /** 1분마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.policy.expire-cron}")
    public void runScheduled() {
        actions.expire(Instant.now(clock));
    }
}
