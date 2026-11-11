package com.bifos.assistant.agent.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 에이전트에 붙어 쓸 수 있는 플러그인의 보이는 이름을 읽는다(docs/features/agent-team/design.md 「할 수 있는 일을 모으기」).
 *
 * <p>{@code connector} 가 {@code agent} 보다 위라 port 를 여기 두고 {@code connector} 가 구현한다. 이름은 카탈로그가 갖고,
 * 카탈로그 캐시가 비었으면 대시보드를 부른다. 그래서 「대시보드를 부르지 않는다」 가 약속인 {@link AgentConnectorBindings} 와 나눈다.
 * 카탈로그를 읽지 못하면 예외 대신 이름을 비운다. 에이전트 목록이 카탈로그 장애로 실패하지 않게 하려는 것이다.
 */
public interface AgentConnectorNames {

    /**
     * 연결과 바인딩이 모두 준비된({@code READY}) 플러그인의 이름이다. 에이전트 번호마다 이름순이고, 없는 에이전트는 빠진다.
     */
    Map<Long, List<String>> readyConnectorNames(Collection<Long> agentIds);
}
