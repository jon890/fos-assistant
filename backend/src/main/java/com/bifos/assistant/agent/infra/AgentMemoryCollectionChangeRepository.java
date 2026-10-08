package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMemoryCollectionChangeRepository extends JpaRepository<AgentMemoryCollectionChange, Long> {

    /** 그 에이전트의 최근 기록 10줄이다. 새것부터 낸다. */
    List<AgentMemoryCollectionChange> findTop10ByAgentIdOrderByIdDesc(Long agentId);
}
