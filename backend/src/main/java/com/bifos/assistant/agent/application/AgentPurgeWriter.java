package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.model.AgentPurgeOutcome;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionChangeRepository;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentToolsetRequestRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 에이전트 하나의 행과 딸린 줄을 한 트랜잭션으로 지운다(ADR-20261009 / agent-purge).
 *
 * <p>에이전트 행을 쓰기 잠금으로 잡은 뒤 대기 여부를 같은 트랜잭션에서 본다. 위 패키지의 표는 {@link AgentPurgeParticipant} 가
 * 맡고, 이 패키지의 표는 여기서 지운다. 에이전트 행을 가리키는 FK 가 있으므로 딸린 줄을 모두 지우거나 비운 뒤 행을 지운다.
 */
@Component
@RequiredArgsConstructor
public class AgentPurgeWriter {

    private final AgentRepository agents;
    private final AgentMemoryCollectionRepository collections;
    private final AgentMemoryCollectionChangeRepository collectionChanges;
    private final AgentToolsetRequestRepository toolsetRequests;
    private final List<AgentPurgeParticipant> participants;

    /** 그 에이전트를 지금 지울 수 있는가. 지운 지 cutoff 앞이고 기다리라는 참여자가 없어야 한다. 잠그지 않는다. */
    @Transactional(readOnly = true)
    public boolean ready(Long agentId, Instant cutoff) {
        if (!purgeable(agents.findById(agentId).orElse(null), cutoff)) {
            return false;
        }
        return participants.stream().noneMatch(participant -> participant.blocksPurge(agentId));
    }

    /** 지운 지 cutoff 앞인 에이전트 하나를 지운다. */
    @Transactional
    public AgentPurgeOutcome purge(Long agentId, Instant cutoff) {
        if (!purgeable(agents.findByIdForUpdate(agentId).orElse(null), cutoff)) {
            return AgentPurgeOutcome.GONE;
        }
        if (participants.stream().anyMatch(participant -> participant.blocksPurge(agentId))) {
            return AgentPurgeOutcome.WAITING;
        }
        participants.forEach(participant -> participant.release(agentId));
        toolsetRequests.deleteAllOfAgent(agentId);
        collectionChanges.deleteAllOfAgent(agentId);
        collections.deleteAllOfAgent(agentId);
        agents.deletePurged(agentId);
        return AgentPurgeOutcome.PURGED;
    }

    /** 행이 있고, 지운 에이전트이고, 지운 시각이 cutoff 뒤가 아니어야 지울 수 있다. */
    private static boolean purgeable(Agent agent, Instant cutoff) {
        return agent != null && agent.deletedAt() != null && !agent.deletedAt().isAfter(cutoff);
    }
}
