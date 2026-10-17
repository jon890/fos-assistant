package com.bifos.assistant.connector.approval.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 기동 전에 끊긴 승인 줄을 마무리한다. 미소비 금융 실행만 실패로 끝내며 다시 실행하지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorActionSweeper {
    private final ConnectorActionService actions;
    private final Clock clock = Clock.systemUTC();


    /**
     * 끊긴 실행 기록의 정리 뒤, 대화를 깨우는 기동 훑기 앞에 돈다. 결과를 모르는 줄로 바뀐 것을 깨우기가 함께 전한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(5)
    public void sweep() {
        int interrupted = actions.markInterrupted(Instant.now(clock));
        if (interrupted > 0) {
            log.info("기동 전에 끊긴 승인 줄 {}건을 정리했습니다", interrupted);
        }
    }
}
