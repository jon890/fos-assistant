package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentMemoryCollectionChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMemoryCollectionChangeRepository extends JpaRepository<AgentMemoryCollectionChange, Long> {

    /** 그 에이전트의 최근 기록 10줄이다. 새것부터 낸다. */
    List<AgentMemoryCollectionChange> findTop10ByAgentIdOrderByIdDesc(Long agentId);

    /** 지운 에이전트를 정리할 때 그 에이전트의 Memory 허용 변경 기록을 모두 지운다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AgentMemoryCollectionChange c where c.agentId = :agentId")
    int deleteAllOfAgent(@Param("agentId") Long agentId);
}
