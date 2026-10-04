package com.bifos.assistant.chat.application;

import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 이전 프로세스가 {@code RUNNING} 으로 남긴 전달 시도를 기동할 때 닫는다(ADR-070).
 *
 * <p>알림 줄은 저장했는데 부모 turn 의 실행 줄이 생기기 전에 내려간 전달은 실행 줄이 없어 기동 정리({@link
 * RestartReconciler})가 보지 못한다. 그 시도를 「실패(중단)」 로 닫아 사용자가 다시 전달할 수 있게 한다.
 *
 * <p>기준 시각은 이 빈을 만든 시각이다. 웹 서버는 {@link ApplicationReadyEvent} 전에 요청을 받기 시작하므로, 그 사이
 * 이 프로세스가 시작한 시도는 기준 시각 뒤에 시작한다. 그 시도는 살아 있는 turn 의 것이라 건드리지 않는다.
 *
 * <p>{@code @Order(5)} 는 기동 정리의 묻기({@code @Order(0)})가 시작한 뒤, 기동 뒤 깨우기({@link
 * NextTurnDispatcher#dispatchAfterStartup}, {@code @Order(10)})보다 먼저라는 뜻이다. 실행 줄이 아직 {@code RUNNING} 인
 * 시도는 여기서 건너뛰고, 기동 정리가 그 줄을 정할 때 {@link RecoveredRunRecorder} 가 닫는다. 닫은 묶음의 결과는 이미
 * 전했다고 적혀 있어 기동 뒤 깨우기가 다시 열지 않는다.
 */
@Slf4j
@Component
public class ResultDeliveryRecovery {

    private final ResultDeliveryRecorder recorder;
    private final Instant startedAt;

    public ResultDeliveryRecovery(ResultDeliveryRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.startedAt = clock.instant();
    }

    /** 기준 시각 전에 시작한 시도를 닫는다. 실패해도 기동을 멈추지 않는다. 남은 시도는 다음 기동이 닫는다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(5)
    public void closeAfterStartup() {
        try {
            int closed = recorder.closeLeftovers(startedAt);
            if (closed > 0) {
                log.info("기동 전에 남은 전달 시도 {}건을 닫았습니다", closed);
            }
        } catch (RuntimeException ex) {
            log.error("기동 전에 남은 전달 시도를 닫지 못했다", ex);
        }
    }
}
