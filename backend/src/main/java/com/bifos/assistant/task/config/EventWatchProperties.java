package com.bifos.assistant.task.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 사건 감지의 설정이다(ADR-20261105 / event-watch). 값을 고른 까닭은 {@code docs/features/proactive/design.md} 의 「결정」 이 갖는다.
 *
 * @param checkCron 목록 확인이 도는 때(초 포함 6필드). 일정이 이 값을 설정 이름으로 읽는다. {@code -} 이면 돌지 않는다
 * @param overlap 확인 지점보다 이만큼 앞부터 겹쳐 받는다
 * @param maxRunsPerDay 바인딩마다 그 시간대의 하루에 여는 사건 실행 수
 * @param maxItemsPerRun 사건 실행 하나의 입력에 싣는 새 항목 수
 * @param failureThreshold 다시 연결 알림을 내는 연결 문제의 연속 실패 수. 15분마다라 4 는 한 시간이다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.event-watch")
public record EventWatchProperties(
    @DefaultValue("0 */15 * * * *") String checkCron,
    @DefaultValue("30m") Duration overlap,
    @DefaultValue("12") int maxRunsPerDay,
    @DefaultValue("20") int maxItemsPerRun,
    @DefaultValue("4") int failureThreshold) {

    /** 0 이하의 값이면 기동을 멈춘다. 상한이 0 이면 사건 감지가 켜져도 돌지 않는데 알아채지 못한다. */
    public EventWatchProperties {
        if (overlap == null || overlap.isNegative() || maxRunsPerDay <= 0 || maxItemsPerRun <= 0 || failureThreshold <= 0) {
            throw new IllegalStateException("assistant.event-watch values must be positive");
        }
    }
}
