package com.bifos.assistant.task.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정해 둔 간격마다 발화기와 시작 단계를 차례로 부른다(ADR-072).
 *
 * <p>간격은 {@code assistant.task.dispatch-cron} 이 정하고 검사에서는 {@code -} 로 끈다. 일정은 기동 정리보다 먼저 돌기
 * 시작하므로 {@link TaskRunRecovery} 가 끝나기 전에는 아무것도 하지 않는다. 같은 예약 작업이 차례로 돌므로 시작 단계가
 * 같은 줄을 두 번 함께 열지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDispatcher {

    private final TaskFiring firing;
    private final TaskRunStarter starter;
    private final TaskRunRecovery recovery;
    private final Clock clock;

    @Scheduled(cron = "${assistant.task.dispatch-cron}")
    public void runScheduled() {
        if (!recovery.finished()) {
            return;
        }
        tick(clock.instant());
    }

    /** 발화를 만든 뒤 기다리는 발화를 연다. 발화가 실패해도 시작 단계는 돈다. */
    public void tick(Instant now) {
        try {
            firing.fireDue(now);
        } catch (RuntimeException ex) {
            log.warn("예약 작업 발화기가 실패했다", ex);
        }
        try {
            starter.startQueued(now);
        } catch (RuntimeException ex) {
            log.warn("예약 작업 시작 단계가 실패했다", ex);
        }
    }
}
