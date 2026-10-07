package com.bifos.assistant.connector.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 반영 예정 시각이 지난 바인딩의 반영 맞추기를 돌리는 일정이다(ADR-20261007 / connector-live-reload). 무엇을 바꾸는지는
 * {@link ConnectorBindingService#applyDue()} 가 갖는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorBindingApplier {
    private final ConnectorBindingService bindings;

    /** 30초마다 돈다. 검사에서는 {@code -} 로 끄고 본체를 직접 부른다. */
    @Scheduled(cron = "${assistant.connector.binding.apply-cron}")
    public void runScheduled() {
        bindings.applyDue();
    }
}
