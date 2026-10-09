package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.application.AgentPurgeParticipant;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 에이전트를 정리할 때 커넥터 쪽을 맡는다(ADR-20261009 / agent-purge).
 *
 * <p>바인딩은 지운다. 연결은 사용자의 것이라 남기고 옛 에이전트 칸만 비운다. 승인 줄은 외부 서비스에 쓴 것의 이력이고 남은 대화의
 * 승인 카드가 읽으므로 남기고 에이전트만 비운다. 비어 있는 승인 줄은 승인해도 바인딩을 찾지 못해 실행하지 않고 끝난다. 부르는 쪽의 정리
 * 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ConnectorAgentPurge implements AgentPurgeParticipant {

    private final ConnectorBindingRepository bindings;
    private final ConnectorConnectionRepository connections;
    private final ConnectorActionRepository actions;

    @Override
    public boolean blocksPurge(Long agentId) {
        return false;
    }

    @Override
    public void release(Long agentId) {
        bindings.deleteAllOfAgent(agentId);
        connections.clearAgentOf(agentId);
        actions.clearAgentOf(agentId);
    }
}
