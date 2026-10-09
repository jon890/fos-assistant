package com.bifos.assistant.usage.domain;

/**
 * 실행이 보낸 Hermes session 하나와 그 session 을 가진 profile 이다.
 *
 * <p>지운 대화의 session 을 Hermes 에서 지울 때 쓴다. 에이전트 번호로 그 profile 의 API 주소를 찾는다.
 *
 * @param agentId 실행한 에이전트. 에이전트 없이 돈 실행이면 null 이다
 */
public record ExecutionSessionRef(Long agentId, String profileName, String sessionId) {}
