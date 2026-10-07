package com.bifos.assistant.usage.domain;

/**
 * 실행 하나가 어느 에이전트로 돌았는지만 담는다.
 *
 * @param executionId 실행 번호
 * @param agentId 에이전트 번호. 에이전트에 묶이지 않은 시스템 실행이면 null
 */
public record ExecutionAgentRef(Long executionId, Long agentId) {
}
