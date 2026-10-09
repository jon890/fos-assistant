package com.bifos.assistant.connector.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 선택지 조회, 등록, 연결 확인을 사용자마다 제한하는 한도다({@code backend/docs/flow.md} 의 「사용자별 호출 제한」).
 *
 * @param maxConcurrentCalls 한 사용자가 동시에 돌릴 수 있는 호출 수
 * @param callsPerMinute 한 사용자가 60초 동안 돌릴 수 있는 호출 수
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector")
public record ConnectorProperties(int maxConcurrentCalls, int callsPerMinute) {

    /** 값이 비었거나 0 이하이면 기동을 멈춘다. 0 이면 모든 호출이 거절되는데 기동은 성공해 알아채지 못한다. */
    public ConnectorProperties {
        requirePositive("max-concurrent-calls", maxConcurrentCalls);
        requirePositive("calls-per-minute", callsPerMinute);
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalStateException("assistant.connector." + name + " must be at least 1: " + value);
        }
    }
}
