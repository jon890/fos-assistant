package com.bifos.assistant.agent.domain;

/**
 * 에이전트 한 줄을 처음 저장했다는 사건이다.
 *
 * <p>저장소의 저장이 끝난 직후 한 번 나간다. 에이전트를 만드는 경로가 여럿이라, 새 에이전트에 딸려야 하는 줄을 경로마다
 * 넣지 않고 이 사건 하나로 넣는다.
 */
public record AgentCreated(Agent agent) {}
