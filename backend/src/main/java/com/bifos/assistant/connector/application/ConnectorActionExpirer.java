package com.bifos.assistant.connector.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 답이 없는 승인 요청을 만료로 바꾸고 오래 남은 실행 줄을 정리하는 일정이다(ADR-050). 무엇을 바꾸는지는 {@link ConnectorActionService#expire} 가 갖는다. */
@Component
@RequiredArgsConstructor
public class ConnectorActionExpirer {
    private final ConnectorActionService actions;
    /** 실행의 시간 제한(60초)보다 넉넉히 길다. 이보다 오래 {@code EXECUTING} 이면 결과를 적지 못한 것으로 본다. */
    private static final Duration STALE_EXECUTION = Duration.ofMinutes(5);

    private final Clock clock = Clock.systemUTC();

    /** 1분마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.policy.expire-cron}")
    public void runScheduled() {
        Instant now = Instant.now(clock);
        actions.expire(now);
        actions.markStale(now.minus(STALE_EXECUTION), now);
    }
}
