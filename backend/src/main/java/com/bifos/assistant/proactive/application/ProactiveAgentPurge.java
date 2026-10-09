package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentPurgeParticipant;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 에이전트를 정리할 때 먼저 살펴보기의 설정과 기록을 지운다(ADR-20261009 / agent-purge).
 *
 * <p>살펴보기의 자식 표(발견, 문제, 평가, 자율 판정, 자율 실행, 판단 피드백)는 MySQL FK 의 {@code ON DELETE CASCADE} 와
 * {@code ON DELETE SET NULL} 이 함께 지우거나 비운다. 비용은 {@code agent_execution} 에 남는다. 부르는 쪽의 정리 트랜잭션 안에서만
 * 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ProactiveAgentPurge implements AgentPurgeParticipant {

    private final ProactiveLoopSettingRepository settings;
    private final ProactiveCheckRepository checks;

    @Override
    public boolean blocksPurge(Long agentId) {
        return false;
    }

    @Override
    public void release(Long agentId) {
        settings.deleteAllOfAgent(agentId);
        checks.deleteAllOfAgent(agentId);
    }
}
