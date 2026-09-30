package com.bifos.assistant.orchestration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP {@code agent_delegate} 로 다른 에이전트에게 맡길 때의 한도다(ADR-017 「도구 넷과 한도」).
 *
 * @param maxDepth 새 자식이 가질 수 있는 가장 깊은 깊이. 사용자가 부른 실행이 0 이다
 * @param maxConcurrentChildren 한 뿌리 실행 아래에서 동시에 도는 위임 자식 수
 * @param maxActive 서버 전체에서 동시에 도는 위임 수
 * @param submitTimeout 도구가 Hermes 제출을 기다리는 시간. 넘으면 실행 번호만 돌려준다
 * @param outputMaxChars 실행 줄에 적는 답의 길이 상한. 넘으면 자르고 잘렸다는 한 줄을 붙인다
 */
@ConfigurationProperties(prefix = "assistant.delegation")
public record DelegationProperties(
        int maxDepth, int maxConcurrentChildren, int maxActive, Duration submitTimeout, int outputMaxChars) {
}
