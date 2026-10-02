package com.bifos.assistant.orchestration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MCP {@code agent_delegate} 로 다른 에이전트에게 맡길 때의 한도다(ADR-017 「도구 넷과 한도」).
 *
 * @param maxDepth 새 자식이 가질 수 있는 가장 깊은 깊이. 사용자가 부른 실행이 0 이다
 * @param maxConcurrentChildren 한 루트 실행 아래에서 동시에 도는 위임 자식 수
 * @param maxActive 서버 전체에서 동시에 도는 위임 수
 * @param submitTimeout 도구가 Hermes 제출을 기다리는 시간. 넘으면 실행 번호만 돌려준다
 * @param outputMaxChars 실행 줄에 적는 답의 길이 상한. 넘으면 자르고 잘렸다는 한 줄을 붙인다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.delegation")
public record DelegationProperties(
        int maxDepth, int maxConcurrentChildren, int maxActive, Duration submitTimeout, int outputMaxChars) {

    /** 값이 비었거나 0 이하이면 기동을 멈춘다. 0 이면 모든 위임이 거절되거나 답이 통째로 잘리는데 기동은 성공해 알아채지 못한다. */
    public DelegationProperties {
        requirePositive("max-depth", maxDepth);
        requirePositive("max-concurrent-children", maxConcurrentChildren);
        requirePositive("max-active", maxActive);
        requirePositive("output-max-chars", outputMaxChars);
        if (submitTimeout == null || submitTimeout.isZero() || submitTimeout.isNegative()) {
            throw new IllegalStateException("assistant.delegation.submit-timeout must be positive: " + submitTimeout);
        }
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalStateException("assistant.delegation." + name + " must be at least 1: " + value);
        }
    }
}
