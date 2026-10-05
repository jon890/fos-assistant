package com.bifos.assistant.agent.application;

import java.util.Set;

/**
 * 에이전트에 붙은 커넥터 연결을 읽는다(ADR-083).
 *
 * <p>{@code connector} 가 {@code agent} 보다 위 패키지라 여기 port 를 두고 {@code connector} 가 구현한다. 대시보드를 부르지
 * 않고 저장한 바인딩만 읽으므로 대시보드가 응답하지 않아도 대화와 도구 저장이 이 조회 때문에 실패하지 않는다.
 */
public interface AgentConnectorBindings {

    /** 그 에이전트에 붙은 연결이 하나라도 있는가. 상태와 관계없다. */
    boolean hasBindings(Long agentId);

    /**
     * 그 에이전트의 profile 에 설치한 커넥터 MCP 서버 이름이다. 이름순이다.
     *
     * <p>이름을 모르는 바인딩은 빠진다. 그런 바인딩은 옛 커넥터 에이전트의 것뿐이다.
     */
    Set<String> connectorServers(Long agentId);

    /** 그 서버들의 도구가 Hermes 에 등록되는 이름의 앞부분이다. 실행 기록에서 커넥터 도구를 가려내는 데 쓴다. */
    Set<String> connectorToolPrefixes(Long agentId);
}
