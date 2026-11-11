package com.bifos.assistant.agent.application.dto;

import java.util.List;

/**
 * 에이전트가 할 수 있는 일이다(docs/features/agent-team/spec.md 의 DECISION17). 둘 다 이름순이다.
 *
 * @param connectors 연결과 바인딩이 모두 준비된 플러그인의 보이는 이름
 * @param skills 올린 스킬의 이름
 */
public record AgentCapabilities(List<String> connectors, List<String> skills) {

    public static final AgentCapabilities NONE = new AgentCapabilities(List.of(), List.of());


    public AgentCapabilities {
        connectors = List.copyOf(connectors);
        skills = List.copyOf(skills);
    }
}
