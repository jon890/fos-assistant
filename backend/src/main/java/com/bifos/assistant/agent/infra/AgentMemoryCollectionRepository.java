package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMemoryCollectionRepository
        extends JpaRepository<AgentMemoryCollection, AgentMemoryCollectionId> {

    List<AgentMemoryCollection> findByIdAgentId(Long agentId);
}
