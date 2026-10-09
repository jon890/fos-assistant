package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMemoryCollectionRepository extends JpaRepository<AgentMemoryCollection, AgentMemoryCollectionId> {

    List<AgentMemoryCollection> findByIdAgentId(Long agentId);

    /** 지운 에이전트를 정리할 때 그 에이전트의 Memory 허용 줄을 모두 지운다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AgentMemoryCollection c where c.id.agentId = :agentId")
    int deleteAllOfAgent(@Param("agentId") Long agentId);
}
