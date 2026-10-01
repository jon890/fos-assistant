package com.bifos.assistant.connector.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 기동 전에 실행을 보낸 채 끊긴 승인 줄을 결과를 모르는 것으로 마무리한다(ADR-050). 다시 실행하지 않는다. */
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
            log.info("기동 전에 실행을 보낸 채 끊긴 승인 줄 {}건을 결과를 모르는 것으로 바꿨습니다", interrupted);
        }
    }
}
