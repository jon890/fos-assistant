package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentCreated;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 받는 Memory collection 을 읽고, 새 에이전트에 기본 collection 을 준다(ADR-052).
 *
 * <p>커넥터 에이전트와 찾지 못한 에이전트는 아무것도 받지 않는다. 커넥터 에이전트는 줄이 있어도 같다(ADR-045).
 */
@Service
@RequiredArgsConstructor
public class AgentMemoryCollectionService {

    private final AgentMemoryCollectionRepository grants;
    private final AgentRepository agents;
    private final Clock clock;

    /**
     * 그 에이전트의 실행이 받는 collection 이다.
     *
     * @param agentId 실행의 에이전트 번호. 없는 실행이면 null 이다
     */
    @Transactional(readOnly = true)
    public AgentMemoryGrants grantsOf(Long agentId) {
        Optional<Agent> agent = agentId == null ? Optional.empty() : agents.findById(agentId);
        if (agent.isEmpty() || agent.get().connectorManaged()) {
            return AgentMemoryGrants.none();
        }
        List<AgentMemoryCollection> rows = grants.findByIdAgentId(agentId);
        Set<String> collections =
                rows.stream().map(AgentMemoryCollection::collection).collect(Collectors.toSet());
        Set<String> sensitive = rows.stream()
                .filter(AgentMemoryCollection::allowSensitive)
                .map(AgentMemoryCollection::collection)
                .collect(Collectors.toSet());
        return new AgentMemoryGrants(collections, sensitive);
    }

    /**
     * 새로 저장한 에이전트에 기본 collection 을 준다. 커넥터 에이전트에는 주지 않는다.
     *
     * <p>저장을 부른 쪽의 트랜잭션 안에서 돈다. 에이전트 저장이 되돌려지면 이 줄도 함께 되돌려진다.
     */
    @EventListener
    @Transactional
    public void grantDefaultCollection(AgentCreated created) {
        Agent agent = created.agent();
        if (agent.connectorManaged()) {
            return;
        }
        grants.save(AgentMemoryCollection.of(
                agent.id(), AgentMemoryCollection.DEFAULT_COLLECTION, false, clock.instant()));
    }
}
